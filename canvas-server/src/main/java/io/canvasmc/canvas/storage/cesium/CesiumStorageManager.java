package io.canvasmc.canvas.storage.cesium;

import io.canvasmc.canvas.GlobalConfiguration;
import io.cesiumfolia.folia.BackpressureMetrics;
import io.cesiumfolia.folia.CommitPumpConfig;
import io.cesiumfolia.folia.StorageHookAdapter;
import io.cesiumfolia.storage.BinaryKey;
import io.cesiumfolia.storage.StorageKeyCodecs;
import io.cesiumfolia.storage.StorageManifest;
import io.cesiumfolia.storage.StorageNamespace;
import io.cesiumfolia.storage.lmdb.LmdbStorageBackend;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide owner of Canvas's two Cesium durability scopes. */
public final class CesiumStorageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CesiumStorage");
    private static final String MANIFEST_FILE = "manifest.properties";
    private static final String GLOBAL_SCHEMA = "canvas-global-v2";
    private static final String DIMENSIONS_SCHEMA = "canvas-dimensions-v1";
    private static final CesiumStorageManager DISABLED = new CesiumStorageManager();
    private static volatile CesiumStorageManager current = DISABLED;

    private final boolean enabled;
    private final @Nullable Path root;
    private final @Nullable Settings settings;
    private final @Nullable LmdbStorageBackend globalBackend;
    private final @Nullable LmdbStorageBackend dimensionsBackend;
    private final @Nullable StorageHookAdapter global;
    private final @Nullable StorageHookAdapter dimensions;
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    private final AtomicBoolean closing = new AtomicBoolean();

    private CesiumStorageManager() {
        this.enabled = false;
        this.root = null;
        this.settings = null;
        this.globalBackend = null;
        this.dimensionsBackend = null;
        this.global = null;
        this.dimensions = null;
    }

    private CesiumStorageManager(final Settings settings) {
        this.enabled = false;
        this.root = null;
        this.settings = settings;
        this.globalBackend = null;
        this.dimensionsBackend = null;
        this.global = null;
        this.dimensions = null;
    }

    private CesiumStorageManager(final Path root, final Settings settings,
                                 final LmdbStorageBackend globalBackend,
                                 final LmdbStorageBackend dimensionsBackend,
                                 final StorageHookAdapter global,
                                 final StorageHookAdapter dimensions) {
        this.enabled = true;
        this.root = root;
        this.settings = settings;
        this.globalBackend = globalBackend;
        this.dimensionsBackend = dimensionsBackend;
        this.global = global;
        this.dimensions = dimensions;
    }

    public static CesiumStorageManager current() {
        return current;
    }

    /** Disabled mode performs no filesystem access. Enabled mode opens both scopes or neither. */
    public static synchronized CesiumStorageManager open(final Path worldRoot, final boolean existingWorld) {
        Objects.requireNonNull(worldRoot, "worldRoot");
        final Settings settings = Settings.from(GlobalConfiguration.getInstance().cesiumStorage);
        if (!settings.enabled()) {
            if (current.enabled) {
                throw new IllegalStateException("Cannot disable open Cesium storage");
            }
            current = new CesiumStorageManager(settings);
            LOGGER.info("Cesium storage is disabled (configured backend={})", settings.backend());
            return current;
        }

        final Path root = resolveStorageRoot(worldRoot, settings.rootDirectory());
        if (current.enabled) {
            if (root.equals(current.root) && settings.equals(current.settings)) return current;
            throw new IllegalStateException("Cesium storage is already open for " + current.root);
        }
        final Path globalRoot = root.resolve("global");
        final Path dimensionsRoot = root.resolve("dimensions");
        final boolean hasGlobalManifest = Files.isRegularFile(globalRoot.resolve(MANIFEST_FILE));
        final boolean hasDimensionsManifest = Files.isRegularFile(dimensionsRoot.resolve(MANIFEST_FILE));
        if (hasGlobalManifest != hasDimensionsManifest) {
            throw new IllegalStateException("Cesium storage must prepare global and dimensions manifests together");
        }
        final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        preflight(globalRoot, GLOBAL_SCHEMA, dataVersion, existingWorld);
        preflight(dimensionsRoot, DIMENSIONS_SCHEMA, dataVersion, existingWorld);

        final LmdbStorageBackend.Options backendOptions = new LmdbStorageBackend.Options(
            settings.initialMapSizeBytes(), settings.maximumMapSizeBytes(), settings.maximumReaders(),
            settings.maximumValueBytes(), settings.compressionLevel(), settings.closeTimeout(),
            settings.maximumCommitAttempts());
        final CommitPumpConfig pumpConfig = new CommitPumpConfig(
            settings.maxBatchOperations(), settings.backpressureOperationThreshold(),
            settings.backpressureByteThreshold(), settings.initialRetryDelay(),
            settings.maximumRetryDelay(), settings.closeTimeout());
        final Instant createdAt = Instant.now();
        final LmdbStorageBackend.UncleanOpenPolicy uncleanPolicy = settings.allowUncleanRecovery()
            ? LmdbStorageBackend.UncleanOpenPolicy.RECOVER : LmdbStorageBackend.UncleanOpenPolicy.REJECT;

        LmdbStorageBackend openedGlobalBackend = null;
        LmdbStorageBackend openedDimensionsBackend = null;
        StorageHookAdapter openedGlobal = null;
        StorageHookAdapter openedDimensions = null;
        try {
            openedGlobalBackend = LmdbStorageBackend.open(globalRoot,
                manifest(GLOBAL_SCHEMA, dataVersion, createdAt), backendOptions,
                LmdbStorageBackend.OpenMode.READ_WRITE, uncleanPolicy);
            openedGlobal = new StorageHookAdapter(openedGlobalBackend, StorageHookAdapter.Scope.GLOBAL,
                "canvas-global", pumpConfig);
            openedDimensionsBackend = LmdbStorageBackend.open(dimensionsRoot,
                manifest(DIMENSIONS_SCHEMA, dataVersion, createdAt), backendOptions,
                LmdbStorageBackend.OpenMode.READ_WRITE, uncleanPolicy);
            openedDimensions = new StorageHookAdapter(openedDimensionsBackend,
                StorageHookAdapter.Scope.DIMENSION, "canvas-dimensions", pumpConfig);
            current = new CesiumStorageManager(root, settings, openedGlobalBackend,
                openedDimensionsBackend, openedGlobal, openedDimensions);
            LOGGER.info("Cesium storage enabled: backend={}, path={}, schemas=[{}, {}], map={}..{} bytes, readers={}, maxValue={} bytes",
                settings.backend(), root, GLOBAL_SCHEMA, DIMENSIONS_SCHEMA, settings.initialMapSizeBytes(),
                settings.maximumMapSizeBytes(), settings.maximumReaders(), settings.maximumValueBytes());
            return current;
        } catch (final Throwable failure) {
            abortOpened(openedDimensions, openedDimensionsBackend, failure);
            abortOpened(openedGlobal, openedGlobalBackend, failure);
            current = DISABLED;
            throw propagate(failure);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public @Nullable RegionStore regionStore(final RegionStorageInfo info) {
        Objects.requireNonNull(info, "info");
        if (!enabled) return null;
        ensureHealthy();
        final StorageNamespace namespace = switch (info.type()) {
            case "chunk", "chunks" -> StorageNamespace.CHUNKS;
            case "poi" -> StorageNamespace.POI;
            case "entity", "entities" -> StorageNamespace.ENTITIES;
            default -> throw new IllegalArgumentException("Unsupported Cesium region type: " + info.type());
        };
        return new RegionStore(this, dimensions(), namespace, info.dimension().identifier().toString());
    }

    public SavedDataStore savedData(final ResourceKey<Level> dimension) {
        Objects.requireNonNull(dimension, "dimension");
        requireEnabled();
        ensureHealthy();
        return new SavedDataStore(this, dimensions(), dimension.identifier().toString());
    }

    public Optional<byte[]> readPlayer(final UUID uuid) {
        return readGlobal(StorageNamespace.PLAYERS, uuid);
    }

    public CompletionStage<Void> writePlayer(final UUID uuid, final @Nullable byte[] value) {
        return writeGlobal(StorageNamespace.PLAYERS, uuid, value, "player " + uuid);
    }

    public Optional<byte[]> readAdvancement(final UUID uuid) {
        return readGlobal(StorageNamespace.ADVANCEMENTS, uuid);
    }

    public CompletionStage<Void> writeAdvancement(final UUID uuid, final @Nullable byte[] value) {
        return writeGlobal(StorageNamespace.ADVANCEMENTS, uuid, value, "advancements " + uuid);
    }

    public Optional<byte[]> readStatistics(final UUID uuid) {
        return readGlobal(StorageNamespace.STATISTICS, uuid);
    }

    public CompletionStage<Void> writeStatistics(final UUID uuid, final @Nullable byte[] value) {
        return writeGlobal(StorageNamespace.STATISTICS, uuid, value, "statistics " + uuid);
    }
    public Optional<byte[]> readWorldData() {
        requireEnabled();
        ensureHealthy();
        return joinObserved(global().read(StorageNamespace.WORLD_DATA, StorageKeyCodecs.string("level")),
            "world metadata read");
    }

    public CompletionStage<Void> writeWorldData(final @Nullable byte[] value) {
        requireEnabled();
        ensureHealthy();
        final BinaryKey key = StorageKeyCodecs.string("level");
        return submitObserved(() -> value == null ? global().delete(StorageNamespace.WORLD_DATA, key)
            : global().put(StorageNamespace.WORLD_DATA, key, value), "world metadata write");
    }


    public CompletionStage<Void> observe(final CompletionStage<Void> stage, final String operation) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(operation, "operation");
        stage.whenComplete((ignored, failure) -> {
            if (failure != null) terminalFailure(operation, unwrap(failure));
        });
        return stage;
    }

    /** Clean close drains and marks both manifests clean. Abnormal close aborts without doing so. */
    public void flushAndClose(final boolean clean) {
        if (!enabled || !closing.compareAndSet(false, true)) return;
        final boolean cleanClose = clean && terminalFailure.get() == null;
        Throwable failure = null;
        try {
            failure = await(cleanClose ? dimensions().closeAsync() : dimensions().abortAsync(),
                cleanClose ? "dimensions close" : "dimensions abort", failure);
            failure = await(cleanClose ? global().closeAsync() : global().abortAsync(),
                cleanClose ? "global close" : "global abort", failure);
        } finally {
            logMetrics("dimensions", dimensions().metrics());
            logMetrics("global", global().metrics());
            current = DISABLED;
        }
        if (failure != null) {
            terminalFailure("shutdown", failure);
            throw propagate(failure);
        }
        LOGGER.info("Cesium storage {} at {}", cleanClose ? "closed cleanly" : "aborted uncleanly", root);
    }

    public void configurationReloaded(final GlobalConfiguration.CesiumStorage configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (enabled && !settings().equals(Settings.from(configuration))) {
            LOGGER.warn("Cesium storage settings changed on reload; startup settings remain active until restart");
        } else if (!enabled && configuration.enabled) {
            LOGGER.warn("Cesium storage was enabled on reload and will remain disabled until restart");
        }
    }

    /** Cheap immutable diagnostic snapshot; no filesystem or backend I/O is performed. */
    public Status status() {
        if (!enabled) return Status.disabled(settings == null ? "unconfigured" : settings.backend());
        final Throwable failure = terminalFailure.get();
        final String state = closing.get() ? "closing" : failure == null ? "healthy" : "failed";
        return new Status(true, settings().backend(), root, state, failure == null ? null : failure.toString(),
            scopeStatus(globalBackend(), global().metrics()),
            scopeStatus(dimensionsBackend(), dimensions().metrics()));
    }

    private Optional<byte[]> readGlobal(final StorageNamespace namespace, final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        requireEnabled();
        ensureHealthy();
        return joinObserved(global().read(namespace, StorageKeyCodecs.uuid(uuid)), namespace.databaseName() + " read " + uuid);
    }

    private CompletionStage<Void> writeGlobal(final StorageNamespace namespace, final UUID uuid,
                                               final @Nullable byte[] value, final String operation) {
        Objects.requireNonNull(uuid, "uuid");
        requireEnabled();
        ensureHealthy();
        final BinaryKey key = StorageKeyCodecs.uuid(uuid);
        return submitObserved(() -> value == null ? global().delete(namespace, key)
            : global().put(namespace, key, value), operation);
    }

    private CompletionStage<Void> submitObserved(
        final java.util.function.Supplier<CompletionStage<Void>> submission,
        final String operation
    ) {
        try {
            return observe(submission.get(), operation);
        } catch (final RuntimeException failure) {
            terminalFailure(operation, failure);
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    private void terminalFailure(final String operation, final Throwable failure) {
        if (!terminalFailure.compareAndSet(null, failure)) return;
        LOGGER.error("Cesium terminal failure during {}; requesting abnormal server halt", operation, failure);
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null) server.cesium$storageFailed();
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Cesium storage is disabled");
    }

    private void ensureHealthy() {
        final Throwable failure = terminalFailure.get();
        if (failure != null) throw new IllegalStateException("Cesium storage terminally failed", failure);
        if (closing.get()) throw new IllegalStateException("Cesium storage is closing");
    }

    private StorageHookAdapter global() {
        return Objects.requireNonNull(global, "global adapter");
    }

    private StorageHookAdapter dimensions() {
        return Objects.requireNonNull(dimensions, "dimensions adapter");
    }

    private LmdbStorageBackend globalBackend() {
        return Objects.requireNonNull(globalBackend, "global backend");
    }

    private LmdbStorageBackend dimensionsBackend() {
        return Objects.requireNonNull(dimensionsBackend, "dimensions backend");
    }

    private Settings settings() {
        return Objects.requireNonNull(settings, "settings");
    }

    private static Path resolveStorageRoot(final Path worldRoot, final String configuredRoot) {
        final Path relative;
        try {
            relative = Path.of(configuredRoot);
        } catch (final java.nio.file.InvalidPathException failure) {
            throw new IllegalArgumentException("Invalid cesiumStorage.rootDirectory", failure);
        }
        if (configuredRoot.isBlank() || relative.isAbsolute()) {
            throw new IllegalArgumentException("cesiumStorage.rootDirectory must be a nonblank world-relative path");
        }
        final Path normalizedWorldRoot = worldRoot.toAbsolutePath().normalize();
        final Path resolved = normalizedWorldRoot.resolve(relative).normalize();
        if (resolved.equals(normalizedWorldRoot) || !resolved.startsWith(normalizedWorldRoot)) {
            throw new IllegalArgumentException("cesiumStorage.rootDirectory must remain inside the world root");
        }
        return resolved;
    }

    private static StorageManifest manifest(final String schema, final int dataVersion, final Instant createdAt) {
        return new StorageManifest(LmdbStorageBackend.FORMAT_ID, LmdbStorageBackend.FORMAT_VERSION,
            dataVersion, "zstd", schema, 0L, true, createdAt);
    }

    private static void preflight(final Path scopeRoot, final String schema, final int dataVersion,
                                  final boolean existingWorld) {
        final Path manifest = scopeRoot.resolve(MANIFEST_FILE);
        final boolean hasManifest = Files.isRegularFile(manifest);
        final boolean hasData = Files.isRegularFile(scopeRoot.resolve("data.mdb"));
        if (existingWorld && (!hasManifest || !hasData)) {
            throw new IllegalStateException("Existing world lacks prepared Cesium " + schema + " storage at " + scopeRoot);
        }
        if (!hasManifest) {
            if (hasData || Files.exists(scopeRoot.resolve("lock.mdb")))
                throw new IllegalStateException("Cesium LMDB exists without manifest at " + scopeRoot);
            return;
        }
        if (!hasData) throw new IllegalStateException("Cesium manifest exists without data.mdb at " + scopeRoot);

        final Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(manifest)) {
            properties.load(input);
        } catch (final IOException failure) {
            throw new IllegalStateException("Cannot read Cesium manifest " + manifest, failure);
        }
        requireProperty(properties, "format.id", LmdbStorageBackend.FORMAT_ID, manifest);
        requireProperty(properties, "format.version", Integer.toString(LmdbStorageBackend.FORMAT_VERSION), manifest);
        requireProperty(properties, "minecraft.data-version", Integer.toString(dataVersion), manifest);
        requireProperty(properties, "compression", "zstd", manifest);
        requireProperty(properties, "schema.id", schema, manifest);
    }

    private static void requireProperty(final Properties properties, final String key,
                                        final String expected, final Path manifest) {
        final String actual = properties.getProperty(key);
        if (!expected.equals(actual))
            throw new IllegalStateException("Cesium manifest " + manifest + " has " + key + '=' + actual
                + ", expected " + expected);
    }

    private static void abortOpened(final @Nullable StorageHookAdapter adapter,
                                    final @Nullable LmdbStorageBackend backend,
                                    final Throwable openingFailure) {
        try {
            if (adapter != null) adapter.abortAsync(openingFailure).toCompletableFuture().join();
            else if (backend != null) backend.abortAsync(openingFailure).toCompletableFuture().join();
        } catch (final Throwable abortFailure) {
            openingFailure.addSuppressed(unwrap(abortFailure));
        }
    }

    private static @Nullable Throwable await(final CompletionStage<Void> stage, final String scope,
                                             final @Nullable Throwable previous) {
        try {
            stage.toCompletableFuture().join();
            return previous;
        } catch (final Throwable thrown) {
            final Throwable failure = unwrap(thrown);
            LOGGER.error("Cesium {} failed", scope, failure);
            if (previous == null) return failure;
            previous.addSuppressed(failure);
            return previous;
        }
    }

    private static Throwable unwrap(final Throwable thrown) {
        return (thrown instanceof CompletionException || thrown instanceof ExecutionException)
            && thrown.getCause() != null ? thrown.getCause() : thrown;
    }

    private static RuntimeException propagate(final Throwable failure) {
        return failure instanceof RuntimeException runtime ? runtime : new CompletionException(failure);
    }

    private <T> T joinObserved(final CompletionStage<T> stage, final String operation) {
        try {
            return stage.toCompletableFuture().join();
        } catch (final CompletionException failure) {
            terminalFailure(operation, unwrap(failure));
            throw failure;
        }
    }

    private static void logMetrics(final String scope, final BackpressureMetrics metrics) {
        LOGGER.info("Cesium {} metrics: outstanding={}/{}, highWater={}/{}, coalesced={}, commits={}, failures={}, retries={}, backpressure={}, flushFailures={}",
            scope, metrics.outstandingOperations(), metrics.outstandingBytes(), metrics.highWaterOperations(),
            metrics.highWaterBytes(), metrics.coalescedWrites(), metrics.successfulCommits(),
            metrics.commitFailures(), metrics.retries(), metrics.backpressureEvents(), metrics.flushFailures());
    }

    private static ScopeStatus scopeStatus(final LmdbStorageBackend backend, final BackpressureMetrics metrics) {
        final StorageManifest manifest = backend.manifest();
        return new ScopeStatus(manifest.schemaId(), manifest.generation(), manifest.cleanShutdown(),
            metrics.outstandingOperations(), metrics.outstandingBytes(), metrics.highWaterOperations(),
            metrics.highWaterBytes(), metrics.coalescedWrites(), metrics.successfulCommits(),
            metrics.commitFailures(), metrics.flushFailures(), metrics.retries(),
            metrics.backpressureEvents(), metrics.backpressured(), metrics.acceptingWrites());
    }

    public record Status(boolean enabled, String backend, @Nullable Path path, String state,
                         @Nullable String terminalFailure, @Nullable ScopeStatus global,
                         @Nullable ScopeStatus dimensions) {
        private static Status disabled(final String backend) {
            return new Status(false, backend, null, "disabled", null, null, null);
        }
    }

    public record ScopeStatus(String schemaId, long manifestGeneration, boolean manifestClean,
                              long outstandingOperations, long outstandingBytes,
                              long highWaterOperations, long highWaterBytes, long coalescedWrites,
                              long successfulCommits, long commitFailures, long flushFailures,
                              long retries, long backpressureEvents, boolean backpressured,
                              boolean acceptingWrites) {}

    public static final class RegionStore {
        private final CesiumStorageManager manager;
        private final StorageHookAdapter adapter;
        private final StorageNamespace namespace;
        private final String dimension;

        private RegionStore(final CesiumStorageManager manager, final StorageHookAdapter adapter,
                            final StorageNamespace namespace, final String dimension) {
            this.manager = manager;
            this.adapter = adapter;
            this.namespace = namespace;
            this.dimension = dimension;
        }

        public boolean contains(final int x, final int z) {
            return read(x, z).isPresent();
        }

        public Optional<byte[]> read(final int x, final int z) {
            manager.ensureHealthy();
            return manager.joinObserved(adapter.read(namespace, StorageKeyCodecs.dimensionChunk(dimension, x, z)),
                namespace.databaseName() + " read " + dimension + " [" + x + ',' + z + ']');
        }

        public CompletionStage<Void> write(final int x, final int z, final @Nullable byte[] value) {
            manager.ensureHealthy();
            final BinaryKey key = StorageKeyCodecs.dimensionChunk(dimension, x, z);
            final String operation = namespace.databaseName() + ' ' + dimension + " [" + x + ',' + z + ']';
            return manager.submitObserved(() -> value == null ? adapter.delete(namespace, key)
                : adapter.put(namespace, key, value), operation);
        }

        public void flush() {
            manager.ensureHealthy();
            manager.joinObserved(manager.observe(adapter.flushAsync(), namespace.databaseName() + " flush for " + dimension),
                namespace.databaseName() + " flush for " + dimension);
        }
    }

    public static final class SavedDataStore {
        private final CesiumStorageManager manager;
        private final StorageHookAdapter adapter;
        private final String dimension;

        private SavedDataStore(final CesiumStorageManager manager, final StorageHookAdapter adapter,
                               final String dimension) {
            this.manager = manager;
            this.adapter = adapter;
            this.dimension = dimension;
        }

        public Optional<byte[]> read(final String id) {
            Objects.requireNonNull(id, "id");
            manager.ensureHealthy();
            return manager.joinObserved(adapter.read(StorageNamespace.SAVED_DATA,
                StorageKeyCodecs.dimensionString(dimension, id)), "saved data read " + dimension + '/' + id);
        }

        public CompletionStage<Void> write(final String id, final @Nullable byte[] value) {
            Objects.requireNonNull(id, "id");
            manager.ensureHealthy();
            final BinaryKey key = StorageKeyCodecs.dimensionString(dimension, id);
            final String operation = "saved data " + dimension + '/' + id;
            return manager.submitObserved(() -> value == null
                ? adapter.delete(StorageNamespace.SAVED_DATA, key)
                : adapter.put(StorageNamespace.SAVED_DATA, key, value), operation);
        }
    }

    private record Settings(boolean enabled, boolean allowUncleanRecovery,
                            String backend, String rootDirectory,
                            long initialMapSizeBytes, long maximumMapSizeBytes, int maximumReaders,
                            int maximumValueBytes, int compressionLevel, int maximumCommitAttempts,
                            int maxBatchOperations, long backpressureOperationThreshold,
                            long backpressureByteThreshold, Duration initialRetryDelay,
                            Duration maximumRetryDelay, Duration closeTimeout) {
        private static Settings from(final GlobalConfiguration.CesiumStorage config) {
            return new Settings(config.enabled, config.allowUncleanRecovery,
                config.backend.toLowerCase(java.util.Locale.ROOT), config.rootDirectory,
                config.initialMapSizeBytes, config.maximumMapSizeBytes, config.maximumReaders,
                config.maximumValueBytes, config.compressionLevel, config.maximumCommitAttempts,
                config.maxBatchOperations, config.backpressureOperationThreshold,
                config.backpressureByteThreshold, Duration.ofMillis(config.initialRetryDelayMillis),
                Duration.ofMillis(config.maximumRetryDelayMillis),
                Duration.ofSeconds(config.closeTimeoutSeconds));
        }
    }
}
