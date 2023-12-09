package slatepowered.inset.datastore;

import slatepowered.inset.codec.CodecContext;
import slatepowered.inset.codec.DataCodec;
import slatepowered.inset.codec.DecodeInput;
import slatepowered.inset.codec.EncodeOutput;
import slatepowered.inset.query.FoundItem;
import slatepowered.inset.query.Query;
import slatepowered.inset.source.DataSourceFindResult;
import slatepowered.inset.source.DataTable;

import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Represents a reference to an item in a datastore.
 *
 * @param <K> The primary key type.
 * @param <T> The data type.
 */
public class DataItem<K, T> extends FoundItem<K, T> {

    public DataItem(Datastore<K, T> datastore, K key) {
        this.datastore = datastore;
        this.key = key;
        this.createdTime = System.currentTimeMillis();
        this.lastReferenceTime = 0;
    }

    /**
     * The datastore this item is a part of.
     */
    private final Datastore<K, T> datastore;

    /**
     * The primary key.
     */
    private final K key;

    /**
     * The loaded value, null if absent.
     */
    private volatile T value;

    /**
     * The creation time as in {@link System#currentTimeMillis()}.
     */
    private final long createdTime;

    /**
     * The last time the cached item was loaded from the database,
     * as an offset onto the created time of this item.
     */
    protected volatile int lastFetchTime = -1;

    /**
     * The last time the cached item was referenced,
     * as an offset onto the created time of this item.
     */
    protected volatile int lastReferenceTime;

    /**
     * Get the primary key for this item.
     *
     * @return The key.
     */
    public K key() {
        return key;
    }

    /**
     * Get whether this item has a value present.
     */
    public boolean isPresent() {
        return value != null;
    }

    /**
     * If a value is present, run the given consumer.
     *
     * @param valueConsumer The consumer.
     * @return This.
     */
    public DataItem<K, T> ifPresent(Consumer<T> valueConsumer) {
        if (isPresent()) {
            valueConsumer.accept(get());
        }

        return this;
    }

    /**
     * Get the loaded value for this item.
     *
     * @return The value or null if absent/unloaded.
     */
    public T get() {
        return value;
    }

    /**
     * Get the loaded value for this item wrapped in an optional.
     *
     * @return The value or an empty optional if absent.
     */
    public Optional<T> optional() {
        return Optional.ofNullable(value);
    }

    /**
     * Get the datastore this item is contained by.
     *
     * @return The datastore.
     */
    public Datastore<K, T> datastore() {
        return datastore;
    }

    /**
     * Get the creation time as in {@link System#currentTimeMillis()}.
     */
    public long timeCreated() {
        return createdTime;
    }

    /**
     * The last time the cached item was loaded from the database.
     * Returns -1 if the item was never pulled/loaded from the database.
     */
    public long lastFetchTime() {
        return lastFetchTime == -1 ? -1 : createdTime + lastFetchTime;
    }

    /**
     * The last time the cached item was referenced.
     */
    public long lastReferenceTime() {
        return createdTime + lastReferenceTime;
    }

    /**
     * Remove this item from the local datastore's cache.
     *
     * @return This.
     */
    public DataItem<K, T> dispose() {
        datastore.getDataCache().remove(this);
        return this;
    }

    /**
     * Create a new default value for this item if the value is absent.
     *
     * @return This.
     */
    public DataItem<K, T> defaultIfAbsent() {
        if (!isPresent()) {
            value = datastore.getDataCodec().createDefault(this);
        }

        return this;
    }

    /**
     * Forces the current value to be disposed and a new default
     * value to be created regardless of whether a current value exists.
     *
     * @return This.
     */
    public DataItem<K, T> resetToDefaults() {
        value = datastore.getDataCodec().createDefault(this);
        return this;
    }

    /**
     * Synchronously serialize and update this item in the remote data storage.
     *
     * @return This.
     */
    public DataItem<K, T> saveSync() {
        if (value == null) {
            return this;
        }

        DataTable table = datastore.getSourceTable();

        // serialize value
        EncodeOutput output = table.getSource().createDocumentSerializationOutput();
        CodecContext context = new CodecContext(datastore.getDataManager());
        output.setSetKey(context, datastore.getDataCodec().getPrimaryKeyFieldName(), key);
        datastore.getDataCodec().encode(context, value, output);

        // perform update
        table.replaceOneSync(output);
        return this;
    }

    /**
     * Asynchronously serialize and update this item in the remote data storage.
     *
     * @see #saveSync()
     * @return This.
     */
    public CompletableFuture<DataItem<K, T>> saveAsync() {
        return CompletableFuture.supplyAsync(this::saveSync, datastore.getDataManager().getExecutorService());
    }

    /**
     * Decode the value for this item for the given nullable input.
     *
     * @param input The input.
     * @return This.
     */
    public DataItem<K, T> decode(DecodeInput input) {
        if (input == null) {
            return this;
        }

        DataCodec<K, T> myCodec = datastore.getDataCodec();
        CodecContext context = datastore.newCodecContext();
        T value = myCodec.construct(context, input);
        myCodec.decode(context, value, input);
        this.value = value;

        return this;
    }

    /**
     * Synchronously fetch and decode the value for this data item.
     *
     * @return This.
     */
    public DataItem<K, T> fetchSync() {
        DataSourceFindResult queryResult = datastore.getSourceTable()
                .findOneSync(Query.byKey(key));
        return decode(queryResult.input()).fetchedNow();
    }

    @Override
    public boolean isPartial() {
        return false;
    }

    @Override
    public DecodeInput input() {
        throw new UnsupportedOperationException("TODO: Create decode input for DataItem"); // TODO
    }

    @Override
    public K getKey(String fieldName, Type expectedType) {
        return key;
    }

    @Override
    public <V> V getField(String fieldName, Type expectedType) {
        throw new UnsupportedOperationException("TODO: Get field on DataItem"); // TODO
    }

    @Override
    public <V> V project(Class<V> vClass) {
        return null; // TODO
    }

    @Override
    public DataItem<K, T> fetch() {
        return this;
    }

    /**
     * Asynchronously fetch and decode the value for this data item.
     *
     * @return The future.
     */
    public CompletableFuture<DataItem<K, T>> fetchAsync() {
        return datastore.getSourceTable()
                .findOneAsync(Query.byKey(key))
                .thenApply(result -> this.decode(result.input()).fetchedNow());
    }

    // Update the lastReferenceTime to represent the
    // current instant
    protected DataItem<K, T> referencedNow() {
        long t = System.currentTimeMillis() - createdTime;
        if (t < 0) // overflow
            t = Integer.MAX_VALUE;
        lastReferenceTime = (int) t;
        return this;
    }

    // Update the lastPullTime to represent the
    // current instant
    protected DataItem<K, T> fetchedNow() {
        long t = System.currentTimeMillis() - createdTime;
        if (t < 0) // overflow
            t = Integer.MAX_VALUE;
        lastFetchTime = (int) t;
        return this;
    }

    @Override
    public String toString() {
        return "DataItem(" + key + " = " + value + ')';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataItem<?, ?> dataItem = (DataItem<?, ?>) o;
        return Objects.equals(datastore, dataItem.datastore) && Objects.equals(key, dataItem.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datastore, key);
    }

}
