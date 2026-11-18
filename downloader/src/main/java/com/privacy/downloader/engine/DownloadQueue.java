package com.privacy.downloader.engine;

import com.privacy.downloader.model.ActiveDownloads;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.util.DownloadDestination;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** The controller thread owns commands, constraint transitions, worker admission and retirement. */
public final class DownloadQueue {
    public interface Store {
        int recoverInterrupted();
        DownloadItem getDownload(String id);
        List<DownloadSegment> getSegments(String id);
        List<DownloadItem> getDownloadsIn(DownloadStatus... states);
        boolean transition(DownloadItem expected, DownloadStatus next, String reason);
        boolean claim(DownloadItem item, File requested, String token) throws IOException;
        boolean saveRetry(DownloadItem replacement, DownloadStatus previousState);
        boolean deleteDownload(DownloadItem expected, boolean deleteFile);
        int clearCompletedExcept(Set<String> owned);
    }

    interface TaskFactory {
        DownloadTask create(DownloadItem item, DownloadTask.TaskListener listener, BlobIntake blob, RequestContext context);
    }

    private static final DownloadStatus[] AUTOMATIC_STATES = {
            DownloadStatus.PENDING, DownloadStatus.CONNECTING, DownloadStatus.RUNNING, DownloadStatus.WAITING_FOR_WIFI,
            DownloadStatus.WAITING_FOR_BATTERY, DownloadStatus.WAITING_FOR_SCHEDULE
    };

    private final Store store;
    private final Executor workerExecutor;
    private final Executor controller;
    private final Executor delivery;
    private final TaskFactory factory;
    private final Runnable activeChanged;
    private final Consumer<String> rowChanged;
    private final Consumer<DownloadItem> terminal;
    private boolean recovered;

    // Read by workers (progress) and the explicit details reader. Only the controller
    // can add/remove owners. A stop request never releases a slot or a filesystem claim.
    private final ConcurrentHashMap<String, Owner> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DownloadItem> pendingTerminals = new ConcurrentHashMap<>();
    // The enqueue path registers this alongside the new row. URLs are never source keys.
    private final ConcurrentHashMap<String, BlobIntake> pendingBlobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    private static final class PendingRequest {
        final String url;
        final RequestContext context;

        PendingRequest(String url, RequestContext context) {
            this.url = url;
            this.context = context;
        }
    }

    private static final class Owner {
        final DownloadTask task;
        final String id;
        final String token;
        final BlobIntake blob;
        volatile boolean stopping;
        volatile boolean exited;
        Removal removal; // controller only

        Owner(DownloadTask task, BlobIntake blob) {
            this.task = task;
            this.blob = blob;
            this.id = task.getItem().getId();
            this.token = task.getItem().getAttemptToken();
        }

        boolean matches(DownloadItem item) {
            return item != null && token.equals(item.getAttemptToken());
        }

        boolean canDeliverProgress() {
            return !stopping && !exited && task.getItem().getStatus().isTransferring();
        }
    }

    private static final class Removal {
        boolean deleteFile;
        final List<Consumer<Boolean>> results = new ArrayList<>();
    }

    DownloadQueue(Store store, Executor workerExecutor, Executor controller, Executor delivery, TaskFactory factory,
                  Runnable activeChanged, Consumer<String> rowChanged, Consumer<DownloadItem> terminal) {
        this.store = store;
        this.workerExecutor = workerExecutor;
        this.controller = controller;
        this.delivery = delivery;
        this.factory = factory;
        this.activeChanged = activeChanged;
        this.rowChanged = rowChanged;
        this.terminal = terminal;
    }

    static boolean isBlob(String url) {
        return url != null && url.startsWith("blob:");
    }

    void registerBlob(String jobId, BlobIntake intake) {
        pendingBlobs.put(jobId, intake);
    }

    void registerRequest(DownloadItem item, RequestContext context) {
        context = context.forTarget(item.getUrl());
        if (context.hasWebSession()) pendingRequests.put(item.getId(), new PendingRequest(item.getUrl(), context));
    }

    private void releasePendingSources(String jobId) {
        pendingRequests.remove(jobId);
        releasePendingBlob(jobId);
    }

    private void releasePendingBlob(String jobId) {
        BlobIntake intake = pendingBlobs.remove(jobId);
        if (intake != null) intake.close();
    }

    void reconcile(QueuePolicy.Constraints constraints) {
        if (!recovered) {
            // There are no in-process owners on the first pass. Leave pauses, waits and history alone.
            if (store.recoverInterrupted() != 0) rowChanged.accept(null);
            recovered = true;
        }
        List<DownloadItem> queue = store.getDownloadsIn(AUTOMATIC_STATES);
        List<String> ready = new ArrayList<>();
        for (DownloadItem item : queue) {
            if (item.getStatus().isTransferring() && !owners.containsKey(item.getId())) {
                // For example, an SQLite error during retirement can leave a token but no thread.
                failUnfinishedWorker(item);
                continue;
            }
            if (isBlob(item.getUrl()) && !owners.containsKey(item.getId())) {
                BlobIntake intake = pendingBlobs.get(item.getId());
                if (intake == null || !intake.isPending()) {
                    releasePendingBlob(item.getId());
                    DownloadItem failed = change(item, QueuePolicy::automatic, DownloadStatus.FAILED, BlobIntake.SOURCE_GONE);
                    if (failed != null) postTerminal(failed);
                    continue;
                }
            }
            DownloadStatus target = constraints.eligibleState(item);
            if (target.isWaiting()) {
                // Evaluate constraints even at capacity, and again for jobs already waiting.
                DownloadItem stopped = change(item, QueuePolicy::automatic, target, constraints.reason(target));
                if (stopped != null) stopOwner(stopped);
            } else {
                DownloadItem pending = change(item, DownloadStatus::isWaiting, DownloadStatus.PENDING, null);
                if (pending != null || item.getStatus() == DownloadStatus.PENDING) ready.add(item.getId());
            }
        }
        for (String id : ready) {
            if (owners.size() >= constraints.slots) break;
            if (!owners.containsKey(id)) admit(id);
        }
    }

    void pause(String id) {
        pause(store.getDownload(id));
    }

    private void pause(DownloadItem item) {
        DownloadItem paused = change(item, QueuePolicy::automatic, DownloadStatus.PAUSED, null);
        if (paused != null) {
            releasePendingSources(paused.getId());
            stopOwner(paused);
        }
    }

    void pauseAll() {
        for (DownloadItem item : store.getDownloadsIn(AUTOMATIC_STATES)) pause(item);
    }

    void cancel(String id) {
        DownloadItem stopped = change(store.getDownload(id), QueuePolicy::mayCancel, DownloadStatus.CANCELLED, null);
        if (stopped != null) {
            releasePendingSources(id);
            stopOwner(stopped);
        }
    }

    void resume(String id) {
        resume(store.getDownload(id));
    }

    private void resume(DownloadItem row) {
        if (row == null || isRemoving(row.getId())) return;
        if (row.getStatus() == DownloadStatus.PAUSED || row.getStatus() == DownloadStatus.FAILED) {
            pendingTerminals.remove(row.getId());
            change(row, state -> state == DownloadStatus.PAUSED || state == DownloadStatus.FAILED, DownloadStatus.PENDING, null);
        }
        // Automatic waits already opt in to reconciliation; retry never bypasses a constraint.
    }

    void resumeAll() {
        for (DownloadItem item : store.getDownloadsIn(DownloadStatus.PAUSED, DownloadStatus.FAILED)) resume(item);
    }

    static boolean canStartOver(DownloadItem item) {
        return QueuePolicy.mayRetry(item.getStatus()) && TransferIntegrity.isDirectHttp(item);
    }

    boolean restart(String id) {
        // Do not reset or release the staging claim while a stopped worker can still use it.
        if (owners.containsKey(id)) return false;
        DownloadItem row = store.getDownload(id);
        if (row == null || !canStartOver(row)) return false;
        DownloadStatus previous = row.getStatus();
        TransferIntegrity.resetForFullDownload(row);
        pendingTerminals.remove(id);
        if (!store.saveRetry(row, previous)) return false;
        pendingRequests.remove(id);
        rowChanged.accept(id);
        return true;
    }

    boolean updateUrl(String id, String url) {
        if (url == null || (RequestContext.origin(url) == null && !url.startsWith("data:")) || owners.containsKey(id)) return false;
        DownloadItem row = store.getDownload(id);
        if (row == null || isBlob(row.getUrl()) || (!QueuePolicy.mayRetry(row.getStatus()) && row.getStatus() != DownloadStatus.PENDING)
                || TransferIntegrity.isDirectHttp(row) != TransferIntegrity.isDirectHttp(row, url)) return false;
        if (!url.equals(row.getUrl())) {
            boolean mux = StreamDetector.needsMux(row);
            row.setUrl(url);
            if (StreamDetector.needsMux(row) != mux) return false;
            if (TransferIntegrity.isDirectHttp(row)) TransferIntegrity.clearRepresentation(row);
        }
        // Preserve a staged checkpoint; the next attempt must still verify its representation.
        if (row.getTempPath() != null) row.setSegments(store.getSegments(id));
        pendingTerminals.remove(id);
        if (!store.saveRetry(row, row.getStatus())) return false;
        pendingRequests.remove(id);
        rowChanged.accept(id);
        return true;
    }

    void delete(String id, boolean deleteFile, Consumer<Boolean> result) {
        Owner owner = owners.get(id);
        pendingTerminals.remove(id);
        if (owner != null) {
            if (owner.removal == null) owner.removal = new Removal();
            owner.removal.deleteFile |= deleteFile;
            if (result != null) owner.removal.results.add(result);
            cancel(id); // A process kill during drain leaves a safe, non-runnable row.
        } else {
            releasePendingSources(id);
            boolean removed = false;
            try {
                DownloadItem row = store.getDownload(id);
                removed = row != null && store.deleteDownload(row, deleteFile);
                if (removed) rowChanged.accept(id);
            } finally {
                if (result != null) result.accept(removed);
            }
        }
    }

    void clearCompleted() {
        Set<String> owned = new HashSet<>(owners.keySet());
        for (String id : owned) {
            DownloadItem row = store.getDownload(id);
            if (row != null && row.getStatus() == DownloadStatus.COMPLETED) delete(id, false, null);
        }
        // A buffered completion must not outlive an explicitly cleared history row.
        pendingTerminals.entrySet().removeIf(entry -> entry.getValue().getStatus() == DownloadStatus.COMPLETED);
        if (store.clearCompletedExcept(owned) != 0) rowChanged.accept(null);
    }

    private boolean isRemoving(String id) {
        Owner owner = owners.get(id);
        return owner != null && owner.removal != null;
    }

    /** Worker progress/status can win a CAS before the controller. Re-read instead of losing the command. */
    private DownloadItem change(DownloadItem row, Predicate<DownloadStatus> allowed, DownloadStatus state, String reason) {
        while (row != null && allowed.test(row.getStatus())) {
            if (row.getStatus() == state && Objects.equals(row.getErrorMessage(), reason)) return null;
            if (store.transition(row, state, reason)) {
                row.setStatus(state);
                row.setErrorMessage(reason);
                rowChanged.accept(row.getId());
                return row;
            }
            row = store.getDownload(row.getId());
        }
        return null;
    }

    private void stopOwner(DownloadItem row) {
        Owner owner = owners.get(row.getId());
        if (owner != null && owner.matches(row)) {
            owner.stopping = true; // Suppress already posted progress before asking the worker to drain.
            owner.task.stopFor(row.getStatus(), row.getErrorMessage());
        }
    }

    private void admit(String id) {
        DownloadItem item = store.getDownload(id);
        if (item == null || item.getStatus() != DownloadStatus.PENDING) return;
        if (item.getTempPath() != null && TransferIntegrity.isDirectHttp(item)) item.setSegments(store.getSegments(id));
        pendingTerminals.remove(id);
        try {
            if (!store.claim(item, requestedDestination(item, false), UUID.randomUUID().toString())) return;
            rowChanged.accept(id);
        } catch (IOException e) {
            DownloadItem failed = change(item, state -> state == DownloadStatus.PENDING, DownloadStatus.FAILED, e.getMessage());
            if (failed != null) releasePendingSources(id);
            return;
        }

        // The SQLite claim has assigned the attempt token. No future attempt can take this source.
        BlobIntake blob = isBlob(item.getUrl()) ? pendingBlobs.remove(id) : null;
        Owner owner;
        try {
            PendingRequest request = pendingRequests.remove(id);
            RequestContext context = request != null && Objects.equals(request.url, item.getUrl())
                    ? request.context : RequestContext.anonymous();
            owner = new Owner(factory.create(item, this::onTaskUpdated, blob, context), blob);
        } catch (RuntimeException | LinkageError e) {
            if (blob != null) blob.close();
            DownloadItem failed = change(item, DownloadStatus::isTransferring, DownloadStatus.FAILED, "Could not start the download.");
            if (failed != null) postTerminal(failed);
            return;
        }
        owners.put(id, owner);
        try {
            workerExecutor.execute(() -> {
                try {
                    owner.task.run();
                } catch (RuntimeException | LinkageError unexpected) {
                    // An unhandled worker error must not kill the app process. Retirement below
                    // can fail a still-running row, but doesn't undo a committed outcome or stop.
                } finally {
                    owner.exited = true;
                    controller.execute(() -> retire(owner));
                }
            });
        } catch (RejectedExecutionException unavailable) {
            owner.exited = true;
            retire(owner);
        }
    }

    private DownloadItem failUnfinishedWorker(DownloadItem row) {
        return change(row, DownloadStatus::isTransferring, DownloadStatus.FAILED,
                "The download worker exited before finishing.");
    }

    private void retire(Owner owner) {
        if (owners.get(owner.id) != owner) return;
        try {
            if (owner.removal != null) {
                boolean removed = false;
                try {
                    DownloadItem row = store.getDownload(owner.id);
                    removed = owner.matches(row) && store.deleteDownload(row, owner.removal.deleteFile);
                } finally {
                    for (Consumer<Boolean> result : owner.removal.results) result.accept(removed);
                }
            } else {
                DownloadItem row = store.getDownload(owner.id);
                if (!owner.matches(row)) return;
                if (row.getStatus().isTransferring()) row = failUnfinishedWorker(row);
                if (!owner.stopping && row != null && (row.getStatus() == DownloadStatus.COMPLETED
                        || row.getStatus() == DownloadStatus.FAILED)) postTerminal(row);
            }
        } finally {
            if (owner.blob != null) owner.blob.close();
            owners.remove(owner.id, owner);
            // The worker may have saved a final checkpoint even after pause or a constraint stop.
            rowChanged.accept(owner.id);
        }
    }

    private void postTerminal(DownloadItem row) {
        // The worker has exited; only the persisted outcome can notify. A retry/delete or
        // a newer attempt revokes the queued event before the delivery thread can accept it.
        pendingTerminals.put(row.getId(), row);
        delivery.execute(() -> {
            if (pendingTerminals.remove(row.getId(), row)) terminal.accept(row);
        });
    }

    private void onTaskUpdated(DownloadItem item) {
        Owner owner = owners.get(item.getId());
        if (owner == null || owner.task.getItem() != item || !owner.matches(item) || !owner.canDeliverProgress()) return;
        activeChanged.run();
    }

    int ownerCount() { return owners.size(); }

    /** Capture only on the controller. Never visits the store or a segment. */
    ActiveDownloads snapshotActive(boolean checked) {
        List<ActiveDownloads.Transfer> active = new ArrayList<>(owners.size());
        for (Owner owner : owners.values()) {
            if (!owner.canDeliverProgress()) continue;
            ActiveDownloads.Transfer transfer = new ActiveDownloads.Transfer(owner.task.getItem());
            if (transfer.status.isTransferring() && !owner.stopping && !owner.exited) active.add(transfer);
        }
        return new ActiveDownloads(checked, owners.size(), active);
    }

    /** Once per explicit details refresh, detach live fields/checkpoints from the owning task. */
    DownloadItem activeDetails(String id) {
        Owner owner = owners.get(id);
        if (owner == null || !owner.canDeliverProgress()) return null;
        DownloadItem live = owner.task.getItem(), copy = new DownloadItem();
        List<DownloadSegment> segments;
        synchronized (live) {
            copy.setId(id);
            copy.setAttemptToken(owner.token);
            copy.setFileName(live.getFileName());
            copy.setDestinationPath(live.getDestinationPath());
            copy.setUrl(live.getUrl());
            copy.setMimeType(live.getMimeType());
            copy.setAudioUrl(live.getAudioUrl());
            copy.setAudioBytes(live.getAudioBytes());
            copy.setStatus(live.getStatus());
            copy.setErrorMessage(live.getErrorMessage());
            copy.setSupportsRange(live.isSupportsRange());
            copy.setNumSegments(live.getNumSegments());
            copy.setDownloadedBytes(live.getDownloadedBytes());
            copy.setTotalBytes(live.getTotalBytes());
            copy.setSpeedBytesPerSec(live.getSpeedBytesPerSec());
            segments = live.getSegments();
        }
        List<DownloadSegment> snapshot = new ArrayList<>();
        if (segments != null) for (DownloadSegment segment : segments) {
            synchronized (segment) {
                snapshot.add(new DownloadSegment(id, segment.getIndex(), segment.getStartByte(), segment.getEndByte(),
                        segment.getCurrentByte(), segment.getStatus()));
            }
        }
        copy.setSegments(snapshot);
        return owners.get(id) == owner && copy.getStatus().isTransferring() && owner.canDeliverProgress() ? copy : null;
    }

    static File requestedDestination(DownloadItem item, boolean newJob) throws IOException {
        String path = item.getDestinationPath();
        if (path == null) throw new IOException("Choose a download folder and file name.");
        File proposed = new File(path);
        if (!proposed.isAbsolute() || proposed.getParentFile() == null) throw new IOException("Choose a filesystem download folder.");
        String name = newJob && item.getFileName() != null ? item.getFileName() : proposed.getName();
        DownloadDestination.requireFileName(name);
        if (!name.equals(proposed.getName())) throw new IOException("The file name does not match the destination.");
        if (StreamDetector.needsMux(item)) name = StreamDetector.ensureMp4Extension(name);
        return new File(proposed.getParentFile(), name);
    }
}
