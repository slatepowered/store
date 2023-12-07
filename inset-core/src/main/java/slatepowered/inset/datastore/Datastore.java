package slatepowered.inset.datastore;

import lombok.Builder;
import lombok.Getter;
import slatepowered.inset.DataManager;
import slatepowered.inset.cache.DataCache;
import slatepowered.inset.codec.DataCodec;
import slatepowered.inset.codec.DecodeInput;
import slatepowered.inset.query.FindAllStatus;
import slatepowered.inset.query.Query;
import slatepowered.inset.query.FindResult;
import slatepowered.inset.query.FindStatus;
import slatepowered.inset.source.DataTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Represents a datastore of values of type {@code T} with primary key type {@code K}.
 *
 * @param <K> The primary key type.
 * @param <T> The data type.
 */
@Builder
public class Datastore<K, T> {

    /** The data caching provider. */
    @Getter
    protected final DataCache<K, T> dataCache;

    @Getter
    protected final DataManager dataManager;

    /**
     * The primary key type.
     */
    @Getter
    protected final Class<K> keyClass;

    /** The database table to load/save data from/to. */
    @Getter
    protected final DataTable sourceTable;

    /** The marshaller for the local data objects. */
    @Getter
    protected final DataCodec<K, T> dataCodec;

    /**
     * Get or create a reference data item for the given key.
     *
     * This data item may be loaded if the object by the given key
     * was cached, it may also be a previously created reference instance
     * or a new instance is created.
     *
     * @param key The key.
     * @return The never-null data item.
     */
    public DataItem<K, T> getOrReference(K key) {
        return dataCache.getOrCompute(key, k -> new DataItem<>(this, k));
    }

    /**
     * Get or create a data item with a value present for the given key.
     *
     * @see #getOrReference(Object)
     * @see DataItem#defaultIfAbsent()
     * @param key The key.
     * @return The never-null data item.
     */
    public DataItem<K, T> getOrCreate(K key) {
        return getOrReference(key).defaultIfAbsent();
    }

    /**
     * Get a cached data item by the given key, returning an
     * empty optional if absent.
     *
     * @param key The key.
     * @return The optional with the value if present.
     */
    public Optional<DataItem<K, T>> getOptional(K key) {
        return Optional.ofNullable(dataCache.getOrNull(key));
    }

    /**
     * Get an existent (potentially empty) data item for the given key or
     * return null if absent.
     *
     * @param key The key.
     * @return The data item or null if absent.
     */
    public DataItem<K, T> getOrNull(K key) {
        return dataCache.getOrNull(key);
    }

    /**
     * Find a cached item by the given query. This only includes loaded
     * items. If an item is not loaded it may be ignored by this method
     * and it may return null.
     *
     * The item will also not be fetched from the database after being returned
     * from this method.
     *
     * This action is always performed synchronously.
     *
     * @param query The query.
     * @return The item or null if no loaded item is present.
     */
    @SuppressWarnings("unchecked")
    public DataItem<K, T> findOneCached(Query query) {
        if (query.hasKey()) {
            DataItem<K, T> item = dataCache.getOrNull((K) query.getKey());
            if (item != null && item.isPresent()) {
                item.referencedNow();
                return item;
            }

            return null;
        }

        // iterate over each item and compare the value
        // with the given query
        Predicate<T> comparator = dataCodec.getQueryComparator(query);
        for (DataItem<K, T> item : dataCache) {
            if (!item.isPresent()) {
                continue;
            }

            if (comparator.test(item.get())) {
                item.referencedNow();
                return item;
            }
        }

        return null;
    }

    /**
     * Potentially asynchronously find a database item for the given
     * query. This will first look for a present cached item matching the query
     * and will otherwise attempt to load it from the database.
     *
     * @param query The query.
     * @return The query status object.
     */
    @SuppressWarnings("unchecked")
    public FindStatus<K, T> findOne(Query query) {
        DataItem<K, T> cachedItem = findOneCached(query);
        if (cachedItem != null) {
            return new FindStatus<>(this, query).completeSuccessfully(FindResult.CACHED, cachedItem);
        }

        query = query.qualify(this);

        // asynchronously try to load the item
        // from the datatable
        FindStatus<K, T> queryStatus = new FindStatus<>(this, query);
        getSourceTable().findOneAsync(query)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        queryStatus.completeFailed(throwable);
                        return;
                    }

                    // check if an item was found
                    if (!result.found()) {
                        queryStatus.completeSuccessfully(FindResult.ABSENT, null);
                        return;
                    }

                    DecodeInput input = result.input();
                    K key = (K) input.getOrReadKey(null, keyClass);
                    if (key == null) {
                        queryStatus.completeFailed("Query result doesnt contain a primary key");
                        return;
                    }

                    DataItem<K, T> item = getOrReference(key);
                    item.decode(input);
                    item.fetchedNow();
                    queryStatus.completeSuccessfully(FindResult.FETCHED, item);
                });
        return queryStatus;
    }

    /**
     * Try to find an item by the given key.
     *
     * @see #findOne(Query)
     * @see Query#byKey(Object)
     * @param key The key.
     * @return This.
     */
    public FindStatus<K, T> findOne(K key) {
        return findOne(Query.byKey(key));
    }

    /**
     * Find all cached items matching the given query in the datastore.
     *
     * This action is always performed synchronously.
     *
     * @param query The filter query.
     * @return The list of cached items matching the given filter.
     */
    public List<DataItem<K, T>> findAllCached(Query query) {
        // pre-allocate a list with an estimated size
        int fieldConstraintCount = query.getFieldConstraints().size();
        List<DataItem<K, T>> list = new ArrayList<>(dataCache.size() / (fieldConstraintCount + 1));

        // iterate over each item and compare the value
        // with the given query
        Predicate<T> comparator = fieldConstraintCount > 0 ? dataCodec.getQueryComparator(query) : __ -> true;
        for (DataItem<K, T> item : dataCache) {
            if (!item.isPresent()) {
                continue;
            }

            if (comparator.test(item.get())) {
                item.referencedNow();
                list.add(item);
            }
        }

        return list;
    }

    /**
     * Find all items matching the given query in the database.
     *
     * Note that the aggregation/find operation is never cached and
     * always references the database, the individual items may be resolved
     * from the cache or cached though.
     *
     * @param query The filter query.
     * @return The status of the operation.
     */
    public FindAllStatus<K, T> findAll(Query query) {
        FindAllStatus<K, T> status = new FindAllStatus<>(this, query);
        getSourceTable().findAllAsync(query)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        status.completeFailed(throwable);
                        return;
                    }

                    // 'complete' the operation with the bulk iterable
                    status.completeSuccessfully(result);
                });
        return status;
    }

    /**
     * Lombok generated, have to declare this otherwise the
     * Javadoc task cries.
     *
     * Builds instances of {@link Datastore}. It is recommended not to
     * directly instantiate this class and instead use {@link DataManager#datastore(Class, Class)}
     * to create a builder.
     *
     * @param <K> The key type.
     * @param <T> The value type.
     */
    public static class DatastoreBuilder<K, T> { /* will be generated by lombok */ }

}
