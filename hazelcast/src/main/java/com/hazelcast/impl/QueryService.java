package com.hazelcast.impl;

import static com.hazelcast.impl.ConcurrentMapManager.Record;
import com.hazelcast.query.IndexAwarePredicate;
import com.hazelcast.query.IndexedPredicate;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.RangedPredicate;
import static com.hazelcast.query.RangedPredicate.RangeType.*;

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class QueryService implements Runnable {

    private final Logger logger = Logger.getLogger(QueryService.class.getName());    
    final Node node;
    private volatile boolean running = true;

    final BlockingQueue<Runnable> queryQ = new LinkedBlockingQueue<Runnable>();

    public QueryService(Node node) {
        this.node = node;
    }

    public void run() {
        while (running) {
            Runnable run;
            try {
                run = queryQ.take();
                run.run();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    public void addNewIndex(final Index<Record> index, final long value, final Record record) {
        try {
            queryQ.put(new Runnable() {
                public void run() {
                    index.addNewIndex(value, record);
                }
            });
        } catch (InterruptedException ignore) {
        }
    }

    public void updateIndex(final Index<Record> index, final long oldValue, final long newValue, final Record record) {
        try {
            queryQ.put(new Runnable() {
                public void run() {
                    index.updateIndex(oldValue, newValue, record);
                }
            });
        } catch (InterruptedException ignore) {
        }
    }

    public void removeIndex(final Index<Record> index, final long value, final Record record) {
        try {
            queryQ.put(new Runnable() {
                public void run() {
                    index.removeIndex(value, record);
                }
            });
        } catch (InterruptedException ignore) {
        }
    }

    public static long getLongValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof Boolean) {
            return (Boolean.TRUE.equals(value)) ? 1 : -1;
        } else {
            return value.hashCode();
        }
    }

    public Collection<Record> query(final Collection<Record> allRecords, final Map<String, Index<Record>> namedIndexes, final Predicate predicate) {
        try {
            final BlockingQueue<Collection<Record>> resultQ = new ArrayBlockingQueue<Collection<Record>>(1);
            queryQ.put(new Runnable() {
                public void run() {
                    resultQ.offer(doQuery(allRecords, namedIndexes, predicate));
                }
            });
            return resultQ.take();
        } catch (InterruptedException ignore) {
        }
        return new ArrayList<Record>();
    }

    public Collection<Record> doQuery(Collection<Record> allRecords, Map<String, Index<Record>> namedIndexes, Predicate predicate) {
        Collection<Record> records = null;
        if (predicate != null && predicate instanceof IndexAwarePredicate) {
            List<IndexedPredicate> lsIndexPredicates = new ArrayList<IndexedPredicate>();
            IndexAwarePredicate iap = (IndexAwarePredicate) predicate;
            iap.collectIndexedPredicates(lsIndexPredicates);
            for (IndexedPredicate indexedPredicate : lsIndexPredicates) {
                Index<Record> index = namedIndexes.get(indexedPredicate.getIndexName());
                if (index != null) {
                    Collection<Record> sub;
                    if (!(indexedPredicate instanceof RangedPredicate)) {
                        sub = index.getRecords(getLongValue(indexedPredicate.getValue()));
                    } else {
                        RangedPredicate rangedPredicate = (RangedPredicate) indexedPredicate;
                        RangedPredicate.RangeType type = rangedPredicate.getRangeType();
                        if (rangedPredicate.getRangeType() == RangedPredicate.RangeType.BETWEEN) {
                            sub = index.getSubRecords(getLongValue(rangedPredicate.getFrom()), getLongValue(rangedPredicate.getTo()));
                        } else {
                            boolean equal = (type == LESS_EQUAL || type == GREATER_EQUAL);
                            if (type == LESS || type == LESS_EQUAL) {
                                sub = index.getSubRecords(equal, true, getLongValue(indexedPredicate.getValue()));
                            } else {
                                sub = index.getSubRecords(equal, false, getLongValue(indexedPredicate.getValue()));
                            }
                        }
                    }
                    logger.log(Level.FINEST, node.factory.getName() + " index sub.size " + sub.size());
                    if (records == null) {
                        records = sub;
                    } else {
                        Iterator itCurrentEntries = records.iterator();
                        while (itCurrentEntries.hasNext()) {
                            if (!sub.contains(itCurrentEntries.next())) {
                                itCurrentEntries.remove();
                            }
                        }
                    }
                }
            }
        }
        if (records == null) {
            records = allRecords;
        }
        return records;
    }


}