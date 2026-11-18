package com.privacy.downloader.engine;

import com.privacy.downloader.model.ActiveDownloads;
import com.privacy.downloader.model.DownloadItem;
import com.privacy.downloader.model.DownloadSegment;
import com.privacy.downloader.model.DownloadStatus;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.util.DownloadDestination;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.privacy.downloader.model.DownloadStatus.*;
import static org.junit.Assert.*;

/** Drive the production controller, workers and callback executor in an explicit order (no sleeps). */
public class DownloadQueueTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void activeSummaryVisitsOnlyOwnersAndDoesNotReadHistoryOrSegmentsAtAnyHistorySize() throws Exception {
        for (int history : new int[]{0, 1000, 50_000}) {
            Fixture f = fixture();
            for (int i = 0; i < history; i++) f.add("history-" + i, COMPLETED);
            f.add("first", PENDING);
            f.add("second", PENDING);
            f.reconcile(open(2));
            assertEquals(0, f.store.detailReads); // Fresh jobs have no checkpoint to hydrate.
            for (int n = 0; n < 2; n++) {
                ControlledTask task = f.task(n);
                task.begin();
                task.item.setTotalBytes(100);
                task.item.setDownloadedBytes(20);
                task.item.setSpeedBytesPerSec(3);
                task.item.setSegments(new java.util.AbstractList<DownloadSegment>() {
                    @Override public DownloadSegment get(int index) { throw new AssertionError("segment touched"); }
                    @Override public int size() { throw new AssertionError("segment enumerated"); }
                });
            }
            int tasks = f.store.taskReads, queues = f.store.queueReads;
            ActiveDownloads snapshot = f.queue.snapshotActive(true);
            assertEquals(2, snapshot.owners);
            assertEquals(2, snapshot.transfers.size());
            assertEquals(40, snapshot.downloadedBytes);
            assertEquals(200, snapshot.totalBytes);
            assertEquals(6, snapshot.speed);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.transfers.clear());
            for (int n = 0; n < 100; n++) {
                f.task(0).item.setDownloadedBytes(21 + n);
                ActiveDownloads next = f.queue.snapshotActive(true);
                assertEquals(2, next.transfers.size());
                assertEquals(41 + n, next.downloadedBytes);
            }
            assertEquals(40, snapshot.downloadedBytes);
            assertEquals(tasks, f.store.taskReads);
            assertEquals(queues, f.store.queueReads);
            assertEquals(0, f.store.detailReads);
        }
    }

    @Test public void onlyAdmissionOfAStagedJobLoadsSegmentsAndDetailsAreDetached() throws Exception {
        Fixture f = fixture();
        DownloadItem row = f.add("job", PAUSED);
        row.setTempPath(f.folder.resolve("download-stage.part").toString());
        row.setSegments(List.of(new DownloadSegment("job", 0, 0, 9, 3, RUNNING)));
        f.queue.resume("job");
        assertEquals(0, f.store.detailReads);
        f.reconcile(open(1));
        assertEquals(1, f.store.detailReads);
        DownloadItem worker = f.task(0).item;
        DownloadItem detail = f.queue.activeDetails("job");
        assertNotSame(worker, detail);
        worker.getSegments().get(0).setCurrentByte(5);
        assertEquals(3, detail.getSegments().get(0).getCurrentByte());
        f.queue.pause("job");
        assertNull(f.queue.activeDetails("job"));
        assertEquals(1, f.store.detailReads);
        assertTrue(f.changedRows.contains("job"));
        assertFalse(f.queue.snapshotActive(true).idle()); // The paused owner still has to drain.
    }

    @Test public void restartAfterDrainRejectsAnOldCompletionAndProgress() throws Exception {
        Fixture f = fixture();
        DownloadItem queued = f.add("job", PENDING);
        Path partial = Files.write(f.folder.resolve("download-old.part"), new byte[]{1, 2, 3});
        queued.setTempPath(partial.toString());
        queued.setDownloadedBytes(3);
        queued.setSegments(List.of(new DownloadSegment("job", 0, 0, 7, 3, PAUSED)));
        f.reconcile(open(1));
        ControlledTask first = f.task(0);
        String oldToken = first.item.getAttemptToken();
        first.script = () -> {
            first.begin();
            assertEquals(0, f.delivery.size());
            assertEquals(RUNNING, f.queue.snapshotActive(true).find("job").status);
            f.queue.pause("job");
            assertEquals(PAUSED, f.state("job"));
            assertFalse(f.queue.restart("job"));
            assertFalse(f.store.publish(first.item));
            first.listener.onTaskCompleted(first.item); // Even an incorrectly timed task callback cannot retire it.
            assertEquals(1, f.queue.ownerCount());
        };
        f.workers.step();
        assertEquals(1, f.queue.ownerCount()); // The retirement itself is serialized with commands.
        assertFalse(f.queue.restart("job"));
        f.controller.step();
        assertTrue(f.queue.restart("job"));
        assertFalse(f.queue.restart("job"));
        assertEquals(0, f.store.getSegments("job").size());
        assertNull(f.store.getDownload("job").getTempPath());
        assertTrue(Files.exists(partial)); // Start over does not release the destination or unlink the prior partial.
        f.reconcile(open(1));
        ControlledTask replacement = f.task(1);
        assertNotEquals(oldToken, replacement.item.getAttemptToken());
        assertEquals(queued.getDestinationPath(), replacement.item.getDestinationPath());
        long changes = f.progressChanges.get();
        first.listener.onTaskUpdated(first.item);
        assertEquals(changes, f.progressChanges.get());
        first.listener.onTaskFailed(first.item, "late failure");
        first.listener.onTaskCompleted(first.item);
        assertFalse(f.store.publish(first.item));
        f.delivery.runAll();
        assertEquals(replacement.item.getAttemptToken(), f.queue.snapshotActive(true).find("job").token);
        assertTrue(f.terminal.isEmpty());
        assertEquals(1, f.queue.ownerCount());
        assertEquals(CONNECTING, f.state("job"));
        replacement.script = () -> {
            replacement.begin();
            assertTrue(f.store.publish(replacement.item));
        };
        f.workers.step();
        f.controller.step();
        f.delivery.runAll();
        assertEquals(COMPLETED, f.state("job"));
        assertEquals(1, f.terminal.size());
        assertEquals(replacement.item.getAttemptToken(), f.terminal.get(0).getAttemptToken());
        assertEquals(0, f.queue.ownerCount());
    }

    @Test public void failureQueuedForTheUIIsRevokedByRetry() throws Exception {
        Fixture f = fixture();
        f.add("job", PENDING);
        f.reconcile(open(1));
        ControlledTask first = f.task(0);
        first.script = () -> {
            first.begin();
            first.fail("synthetic failure");
        };
        f.workers.step();
        f.controller.step();
        assertEquals(FAILED, f.state("job"));
        assertEquals(1, f.delivery.size()); // Only the persisted outcome is queued to main.
        f.queue.resume("job");
        f.reconcile(open(1));
        f.queue.resume("job");
        f.reconcile(open(1));
        assertEquals(2, f.tasks.size());
        assertFalse(f.store.workerStatus(first.item, FAILED, "offline stale failure"));
        f.delivery.runAll();
        assertEquals(CONNECTING, f.queue.snapshotActive(true).find("job").status);
        assertTrue(f.terminal.isEmpty());
        assertEquals(CONNECTING, f.state("job"));
    }

    @Test public void committedPublicationWinsOverPauseCancelAndStartOver() throws Exception {
        Fixture f = fixture();
        f.add("job", PENDING);
        f.reconcile(open(1));
        ControlledTask task = f.task(0);
        task.script = () -> {
            task.begin();
            assertTrue(f.store.publish(task.item));
            // Completion is visible in SQLite even while the publisher is still doing its cleanup.
            f.queue.pause("job");
            f.queue.cancel("job");
            assertFalse(f.queue.restart("job"));
            assertEquals(COMPLETED, f.state("job"));
            assertFalse(task.isStopRequested());
            assertEquals(1, f.queue.ownerCount());
        };
        f.workers.step();
        f.controller.step();
        assertFalse(f.queue.restart("job"));
        f.queue.resume("job");
        f.reconcile(open(1));
        f.delivery.runAll();
        assertEquals(1, f.tasks.size());
        assertEquals(COMPLETED, f.state("job"));
        assertEquals(1, f.terminal.size());
    }

    @Test public void removingACompletedRowRevokesItsBufferedNotification() throws Exception {
        Fixture f = fixture();
        f.add("job", PENDING);
        f.reconcile(open(1));
        ControlledTask task = f.task(0);
        task.script = () -> {
            task.begin();
            assertTrue(f.store.publish(task.item));
        };
        f.workers.step();
        f.controller.step();
        assertEquals(1, f.delivery.size());
        f.queue.clearCompleted();
        assertNull(f.store.getDownload("job"));
        f.delivery.runAll();
        assertTrue(f.terminal.isEmpty());
        assertTrue(f.queue.snapshotActive(true).transfers.isEmpty());
    }

    @Test public void clearingHistoryDoesNotReleaseACompletedOwnerUntilItExits() throws Exception {
        Fixture f = fixture();
        f.add("historical", COMPLETED);
        f.add("paused", PAUSED);
        f.add("job", PENDING);
        f.reconcile(open(1));
        ControlledTask task = f.task(0);
        task.script = () -> {
            task.begin();
            assertTrue(f.store.publish(task.item));
            f.queue.clearCompleted();
            assertNull(f.store.getDownload("historical"));
            assertEquals(PAUSED, f.state("paused"));
            assertEquals(COMPLETED, f.state("job"));
            assertEquals(1, f.queue.ownerCount());
        };
        f.workers.step();
        f.controller.step();
        f.delivery.runAll();
        assertNull(f.store.getDownload("job"));
        assertTrue(f.terminal.isEmpty());
    }

    @Test public void workerExceptionDoesNotStrandItsOwnerOrRestartItsJobInALoop() throws Exception {
        Fixture f = fixture();
        f.add("job", PENDING);
        f.add("next", PENDING);
        f.reconcile(open(1));
        f.task(0).script = () -> { throw new IllegalStateException("synthetic worker exception"); };
        f.workers.step();
        f.controller.step();
        f.reconcile(open(1));
        f.delivery.runAll();
        assertEquals(FAILED, f.state("job"));
        assertEquals(CONNECTING, f.state("next"));
        assertEquals(1, f.terminal.size());
        assertEquals("job", f.terminal.get(0).getId());
    }

    @Test public void cancelDominatesPauseInEitherOrderWhileTheWorkerDrains() throws Exception {
        for (boolean pauseFirst : new boolean[]{true, false}) {
            Fixture f = fixture();
            DownloadItem job = f.add("job", PENDING);
            Path staged = Files.write(f.folder.resolve("download-checkpoint.part"), new byte[]{1, 2, 3});
            job.setTempPath(staged.toString());
            f.reconcile(open(1));
            ControlledTask task = f.task(0);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            task.script = () -> {
                task.begin();
                started.countDown();
                await(release);
                assertTrue(task.isStopRequested());
                assertFalse(f.store.publish(task.item));
                assertTrue(Files.exists(staged));
            };
            FutureTask<Void> running = new FutureTask<>(f.workers.take(), null);
            new Thread(running, "queue-test-worker").start();
            try {
                await(started);
                // The controller executes commands serially, while the transfer still owns its file.
                if (pauseFirst) {
                    f.queue.pause("job");
                    assertEquals(PAUSED, f.state("job"));
                    f.queue.cancel("job");
                } else {
                    f.queue.cancel("job");
                    f.queue.pause("job");
                }
                f.queue.cancel("job");
                f.queue.resume("job");
                f.reconcile(open(1));
                assertEquals(CANCELLED, f.state("job"));
                assertEquals(1, f.queue.ownerCount());
                assertEquals(1, f.tasks.size());
                assertEquals(staged.toString(), f.store.getDownload("job").getTempPath());
                f.delivery.runAll();
                assertTrue(f.queue.snapshotActive(true).transfers.isEmpty());
            } finally {
                release.countDown();
                running.get(3, TimeUnit.SECONDS);
            }
            f.controller.step();
            assertEquals(0, f.queue.ownerCount());
            assertEquals(CANCELLED, f.state("job"));
            assertEquals(0, f.delivery.size());
        }
    }

    @Test public void pauseRechecksWhenWorkerChangesConnectingToRunning() throws Exception {
        Fixture f = fixture();
        f.add("job", PENDING);
        f.reconcile(open(1));
        ControlledTask task = f.task(0);
        f.store.beforeTransition = task::begin;
        f.queue.pause("job");
        assertEquals(PAUSED, f.state("job"));
        assertTrue(task.isStopRequested());
        assertFalse(f.store.workerStatus(task.item, FAILED, "stale failure"));
        f.workers.step();
        f.controller.step();
        f.delivery.runAll();
        assertTrue(f.queue.snapshotActive(true).transfers.isEmpty());
        assertTrue(f.terminal.isEmpty());
    }

    @Test public void deleteHoldsTheDestinationAndStagingUntilExit() throws Exception {
        Fixture f = fixture();
        DownloadItem first = f.add("first", PENDING);
        Path staged = Files.write(f.folder.resolve("download-first.part"), new byte[]{1, 2, 3});
        first.setTempPath(staged.toString());
        f.reconcile(open(1));
        List<Boolean> results = new ArrayList<>();
        f.queue.delete("first", true, results::add);
        assertEquals(CANCELLED, f.state("first"));
        assertEquals(staged.toString(), f.store.getDownload("first").getTempPath());
        assertTrue(results.isEmpty());
        Path destination = Path.of(first.getDestinationPath());
        assertFalse(Files.exists(destination));
        DownloadItem second = f.enqueueAt("second", destination); // The reservation, not file existence, forces the suffix.
        assertEquals("first_1.bin", second.getFileName());
        f.reconcile(open(1));
        assertEquals(PENDING, f.state("second"));
        f.workers.step();
        assertNotNull(f.store.getDownload("first"));
        assertTrue(results.isEmpty());
        f.controller.step();
        assertEquals(List.of(true), results);
        assertNull(f.store.getDownload("first"));
        assertEquals(first.getFileName(), f.enqueueAt("third", destination).getFileName());
        f.reconcile(open(1));
        assertEquals(CONNECTING, f.state("second"));
        assertEquals(PENDING, f.state("third"));
    }

    @Test public void relaunchKeepsHistoryAndRechecksInterruptedAndWaitingRows() throws Exception {
        Fixture f = fixture();
        DownloadItem connecting = f.add("connecting", CONNECTING);
        connecting.setAttemptToken("old-connecting");
        DownloadItem running = f.add("running", RUNNING);
        running.setAttemptToken("old-running");
        running.setTempPath(Files.write(f.folder.resolve("download-incomplete.part"), new byte[]{1, 2, 3}).toString());
        running.setDownloadedBytes(3);
        running.setSegments(List.of(new DownloadSegment("running", 0, 0, 5, 3, RUNNING)));
        for (DownloadStatus state : List.of(PAUSED, PENDING, WAITING_FOR_WIFI, WAITING_FOR_SCHEDULE,
                WAITING_FOR_BATTERY, FAILED, CANCELLED, COMPLETED)) f.add(state.name(), state);

        // New volatile controller with the same database records, as after process death.
        f.relaunch();
        f.reconcile(constraints(false, true, false, true, 10));
        assertEquals(0, f.tasks.size());
        for (String id : List.of("connecting", "running", "PENDING", "WAITING_FOR_WIFI", "WAITING_FOR_SCHEDULE", "WAITING_FOR_BATTERY")) {
            assertEquals(WAITING_FOR_SCHEDULE, f.state(id));
        }
        for (DownloadStatus state : List.of(PAUSED, FAILED, CANCELLED, COMPLETED)) assertEquals(state, f.state(state.name()));
        assertNull(f.store.getDownload("running").getAttemptToken());
        assertEquals(3, f.store.getSegments("running").get(0).getCurrentByte());
        assertEquals(running.getTempPath(), f.store.getDownload("running").getTempPath());
        assertFalse(f.store.workerStatus(running, FAILED, "a pre-relaunch mutation"));
        f.reconcile(open(10));
        assertEquals(6, f.tasks.size());
        for (ControlledTask task : f.tasks) assertEquals(CONNECTING, f.state(task.item.getId()));
        assertNotEquals("old-running", f.store.getDownload("running").getAttemptToken());
        assertEquals(running.getDestinationPath(), f.store.getDownload("running").getDestinationPath());
        assertEquals(1, f.store.recoveries);
    }

    @Test public void interruptedPublicationLeavesHardLinkAndShortCopyUntouched() throws Exception {
        Fixture f = fixture();
        for (boolean link : new boolean[]{false, true}) {
            DownloadItem item = f.add("job-" + link, RUNNING);
            item.setAttemptToken("dead-" + link);
            Path staging = Files.write(f.folder.resolve("download-" + link + ".part"), new byte[]{1, 2, 3});
            item.setTempPath(staging.toString());
            Path dest = Path.of(item.getDestinationPath());
            if (link) Files.createLink(dest, staging);
            else Files.write(dest, new byte[]{1});
        }
        f.reconcile(open(10));
        assertEquals(0, f.queue.ownerCount());
        for (boolean link : new boolean[]{false, true}) {
            DownloadItem row = f.store.getDownload("job-" + link);
            assertEquals(FAILED, row.getStatus());
            assertTrue(row.getErrorMessage().contains("occupied"));
            assertEquals("job-" + link + ".bin", row.getFileName());
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(Path.of(row.getTempPath())));
            assertArrayEquals(link ? new byte[]{1, 2, 3} : new byte[]{1}, Files.readAllBytes(Path.of(row.getDestinationPath())));
        }
    }

    @Test public void scheduleEditsAndDisableReconcileInsteadOfLeavingStaleWaits() throws Exception {
        Fixture f = fixture();
        assertTrue(QueuePolicy.withinWindow(23 * 60, 23 * 60, 5 * 60));
        assertTrue(QueuePolicy.withinWindow(0, 23 * 60, 5 * 60));
        assertFalse(QueuePolicy.withinWindow(5 * 60, 23 * 60, 5 * 60));
        f.add("job", PENDING);
        f.add("next", PENDING);
        f.reconcile(new QueuePolicy.Constraints(QueuePolicy.withinWindow(720, 120, 360), false, true, false, "old window", 1));
        assertEquals(WAITING_FOR_SCHEDULE, f.state("job"));
        assertEquals("old window", f.store.getDownload("job").getErrorMessage());
        f.reconcile(new QueuePolicy.Constraints(false, false, true, false, "another window", 1));
        assertEquals("another window", f.store.getDownload("job").getErrorMessage());
        f.reconcile(new QueuePolicy.Constraints(QueuePolicy.withinWindow(720, 660, 840), false, true, false, "includes now", 1));
        assertEquals(CONNECTING, f.state("job"));
        assertEquals(PENDING, f.state("next"));
        f.reconcile(constraints(false, false, true, false, 1));
        assertTrue(f.task(0).isStopRequested());
        assertEquals(WAITING_FOR_SCHEDULE, f.state("job"));
        assertEquals(WAITING_FOR_SCHEDULE, f.state("next"));
        f.reconcile(open(1)); // Disabling schedule takes the same path as entering the window.
        assertEquals(PENDING, f.state("job"));
        assertEquals(PENDING, f.state("next"));
        assertEquals(1, f.tasks.size()); // A draining owner is still counted against capacity.
        f.workers.step();
        f.controller.step();
        f.reconcile(open(1));
        assertEquals(2, f.tasks.size());
        assertEquals(CONNECTING, f.state("job"));
    }

    @Test public void wifiAndBatteryChangesKeepManualPauseDistinct() throws Exception {
        Fixture f = fixture();
        f.add("auto", PENDING);
        f.add("per-job", PENDING).setWifiOnly(true);
        f.reconcile(constraints(true, false, true, true, 1));
        ControlledTask old = f.task(0);
        f.reconcile(constraints(true, false, false, true, 1));
        assertTrue(old.isStopRequested());
        assertEquals(WAITING_FOR_WIFI, f.state("auto"));
        assertEquals(WAITING_FOR_WIFI, f.state("per-job")); // Even the queued job beyond capacity is reconciled.
        f.reconcile(constraints(true, false, true, true, 1)); // Recovery before exit must not start either job yet.
        assertEquals(PENDING, f.state("auto"));
        assertEquals(PENDING, f.state("per-job"));
        assertEquals(1, f.tasks.size());
        f.queue.pause("auto");
        f.reconcile(constraints(true, true, false, false, 1));
        assertEquals(PAUSED, f.state("auto"));
        assertEquals(WAITING_FOR_BATTERY, f.state("per-job"));
        f.workers.step();
        f.controller.step();
        f.reconcile(constraints(true, false, false, false, 1));
        assertEquals(WAITING_FOR_WIFI, f.state("per-job"));
        f.queue.resume("auto");
        f.reconcile(constraints(true, false, false, false, 1));
        assertEquals(CONNECTING, f.state("auto")); // Global Wi-Fi restriction disabled.
        assertEquals(WAITING_FOR_WIFI, f.state("per-job"));
        f.reconcile(constraints(true, false, true, false, 2));
        assertEquals(CONNECTING, f.state("per-job"));
        assertNotEquals(old.item.getAttemptToken(), f.store.getDownload("auto").getAttemptToken());
    }

    @Test public void pauseAllOptsOutOfAutomaticWaitsUntilResumeAll() throws Exception {
        Fixture f = fixture();
        f.add("wifi", WAITING_FOR_WIFI);
        f.add("schedule", WAITING_FOR_SCHEDULE);
        f.add("battery", WAITING_FOR_BATTERY);
        f.add("done", COMPLETED);
        f.queue.pauseAll();
        f.reconcile(open(3));
        assertEquals(0, f.queue.ownerCount());
        for (String id : List.of("wifi", "schedule", "battery")) assertEquals(PAUSED, f.state(id));
        f.queue.resumeAll();
        f.reconcile(open(3));
        assertEquals(3, f.queue.ownerCount());
        assertEquals(COMPLETED, f.state("done"));
    }

    @Test public void aConfirmedBlobSourceBelongsToOneJobAndItsFirstAttemptNotTheUrl() throws Exception {
        Fixture f = fixture();
        String url = "blob:https://example.test/shared";
        f.add("unconfirmed", PENDING).setUrl(url);
        DownloadItem approved = f.add("approved", PENDING);
        approved.setUrl(url);
        approved.setWifiOnly(true);
        CountingPage page = new CountingPage();
        BlobIntake intake = new BlobIntake(1, page);
        f.queue.registerBlob("approved", intake);
        f.reconcile(constraints(true, false, false, false, 1));
        assertEquals(FAILED, f.state("unconfirmed"));
        assertEquals(WAITING_FOR_WIFI, f.state("approved"));
        assertEquals(0, page.starts);
        assertEquals(0, f.queue.ownerCount());
        assertFalse(f.queue.updateUrl("approved", "https://example.test/clip"));

        f.reconcile(open(1));
        ControlledTask first = f.task(0);
        assertEquals("approved", first.item.getId());
        assertNotNull(first.item.getAttemptToken());
        assertSame(intake, first.blob);
        assertEquals(0, page.starts); // Admission assigns ownership; only the worker can actually start it.
        f.queue.pause("approved");
        assertEquals(PAUSED, f.state("approved"));
        assertEquals(1, page.stops);
        assertThrows(IOException.class, intake::start);
        f.workers.step();
        f.controller.step();
        f.queue.resume("approved");
        f.reconcile(open(1));
        assertEquals(FAILED, f.state("approved"));
        assertEquals(1, f.tasks.size());
        assertEquals(0, f.queue.ownerCount());
    }

    @Test public void expiryNavigationAndCancellationReleaseEvenABlobWaitingForConditions() throws Exception {
        for (String ending : List.of("expiry", "navigation", "cancel", "delete")) {
            Fixture f = fixture();
            f.add("queued", PENDING).setUrl("blob:https://example.test/fixture");
            CountingPage page = new CountingPage();
            AtomicLong clock = new AtomicLong();
            BlobIntake intake = new BlobIntake(-1, page, clock::get);
            f.queue.registerBlob("queued", intake);
            QueuePolicy.Constraints waiting = constraints(false, false, true, false, 1);
            f.reconcile(waiting);
            assertEquals(WAITING_FOR_SCHEDULE, f.state("queued"));
            switch (ending) {
                case "expiry": clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(BlobIntake.QUEUED_TIMEOUT_MS)); break;
                case "navigation": intake.abort("page left"); break;
                case "cancel": f.queue.cancel("queued"); break;
                case "delete": f.queue.delete("queued", false, removed -> assertTrue(removed)); break;
                default: throw new AssertionError(ending);
            }
            f.reconcile(waiting);
            if ("delete".equals(ending)) assertNull(f.store.getDownload("queued"));
            else assertEquals("cancel".equals(ending) ? CANCELLED : FAILED, f.state("queued"));
            assertEquals(0, f.workers.size());
            assertEquals(0, page.starts);
            assertEquals(1, page.stops);
        }
    }

    @Test public void relaunchNeverReadsABlobFromPersistedHistoryAlone() throws Exception {
        Fixture f = fixture();
        DownloadItem old = f.add("interrupted", RUNNING);
        old.setUrl("blob:https://example.test/fixture");
        old.setAttemptToken("earlier-attempt");
        f.relaunch();
        f.reconcile(open(1));
        assertEquals(FAILED, f.state("interrupted"));
        assertEquals(BlobIntake.SOURCE_GONE, f.store.getDownload("interrupted").getErrorMessage());
        assertEquals(0, f.workers.size());
    }

    @Test public void filesystemQueueDoesNotTreatASafUriAsADestination() throws Exception {
        Fixture f = fixture();
        DownloadItem item = f.add("not-filesystem", PENDING);
        item.setDestinationPath("content://fixture.documents/tree/downloads/document/out.bin");
        assertThrows(IOException.class, () -> DownloadQueue.requestedDestination(item, true));
        f.reconcile(open(1));
        assertEquals(FAILED, f.state(item.getId()));
        assertEquals(0, f.workers.size());
    }

    @Test public void browserContextIsOwnedByOneJobAndOneAttempt() throws Exception {
        Fixture f = fixture();
        f.add("anonymous", PENDING);
        DownloadItem approved = f.add("approved", PENDING);
        // The two rows have the same URL. Only one was approved with the regular page's context.
        RequestContext page = RequestContext.fromBrowser("https://example.test/secret?q=private", null, false);
        f.queue.registerRequest(approved, page);
        f.reconcile(constraints(false, false, true, false, 2));
        assertEquals(0, f.tasks.size());
        f.reconcile(open(2));
        assertEquals("anonymous", f.task(0).item.getId());
        assertFalse(f.task(0).requestContext.hasWebSession());
        assertSame(page, f.task(1).requestContext);
        f.queue.pause("approved");
        f.workers.runAll();
        f.controller.runAll();
        f.queue.resume("approved");
        f.reconcile(open(2));
        assertEquals("approved", f.task(2).item.getId());
        assertFalse(f.task(2).requestContext.hasWebSession());
    }

    @Test public void linkEditAndProcessRestartCannotReuseQueuedContext() throws Exception {
        for (boolean restartProcess : new boolean[]{false, true}) {
            Fixture f = fixture();
            DownloadItem item = f.add("download", PENDING);
            RequestContext page = RequestContext.fromBrowser("https://example.test/secret", null, false);
            f.queue.registerRequest(item, page);
            if (restartProcess) f.relaunch();
            else assertTrue(f.queue.updateUrl(item.getId(), "https://example.test/different"));
            f.reconcile(open(1));
            assertFalse(f.task(0).requestContext.hasWebSession());
        }
    }

    private static final class CountingPage implements BlobIntake.Page {
        int starts;
        int stops;
        @Override public void start() { starts++; }
        @Override public void acknowledge() {}
        @Override public void stop() { stops++; }
    }

    private static QueuePolicy.Constraints open(int slots) { return constraints(true, false, true, false, slots); }

    private static QueuePolicy.Constraints constraints(boolean scheduled, boolean battery, boolean wifi, boolean wifiOnly, int slots) {
        return new QueuePolicy.Constraints(scheduled, battery, wifi, wifiOnly, "outside scheduled window", slots);
    }

    private Fixture fixture() throws IOException { return new Fixture(directory.newFolder().toPath()); }

    private static void await(CountDownLatch reached) {
        try {
            assertTrue("worker did not reach the expected boundary", reached.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class Fixture {
        final Path folder;
        final MemoryStore store = new MemoryStore();
        final List<ControlledTask> tasks = new ArrayList<>();
        final AtomicLong progressChanges = new AtomicLong();
        final List<String> changedRows = new ArrayList<>();
        final List<DownloadItem> terminal = new ArrayList<>();
        Steps workers, controller, delivery;
        DownloadQueue queue;

        Fixture(Path folder) {
            this.folder = folder;
            relaunch();
        }

        void relaunch() {
            workers = new Steps();
            controller = new Steps();
            delivery = new Steps();
            queue = new DownloadQueue(store, workers, controller, delivery, (row, listener, blob, request) -> {
                ControlledTask task = new ControlledTask(store, row, listener, blob, request);
                tasks.add(task);
                return task;
            }, progressChanges::incrementAndGet, changedRows::add, terminal::add);
        }

        DownloadItem add(String id, DownloadStatus state) {
            return store.add(id, state, folder.resolve(id + ".bin").toFile());
        }

        DownloadItem enqueueAt(String id, Path requested) throws IOException {
            Set<String> names = new HashSet<>();
            for (DownloadItem row : store.rows.values()) names.add(DownloadDestination.key(new File(row.getDestinationPath())));
            return store.add(id, PENDING, DownloadDestination.choose(requested.toFile(), names));
        }

        void reconcile(QueuePolicy.Constraints constraints) { queue.reconcile(constraints); }
        DownloadStatus state(String id) { return store.getDownload(id).getStatus(); }
        ControlledTask task(int n) { return tasks.get(n); }
    }

    private static final class ControlledTask extends DownloadTask {
        final MemoryStore store;
        final BlobIntake blob;
        Runnable script = () -> {};

        ControlledTask(MemoryStore store, DownloadItem row, TaskListener listener, BlobIntake blob, RequestContext request) {
            super(row, null, listener, null, request);
            this.store = store;
            this.blob = blob;
        }

        @Override protected void onStopRequested() {
            if (blob != null) blob.close();
        }

        void begin() {
            try { markRunning(null); } catch (IOException e) { throw new AssertionError(e); }
        }

        @Override public void run() { script.run(); }
        @Override boolean persistStatus(DownloadStatus state, String reason) { return store.workerStatus(item, state, reason); }
    }

    private static final class Steps implements Executor {
        private final Queue<Runnable> pending = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable work) { pending.add(work); }
        synchronized int size() { return pending.size(); }
        synchronized Runnable take() { return pending.remove(); }
        void step() { take().run(); }
        void runAll() { while (size() != 0) step(); }
    }

    /** Returns detached rows like SQLite. Separate host SQLite checks cover the actual predicates and migration. */
    private static final class MemoryStore implements DownloadQueue.Store {
        private final Map<String, DownloadItem> rows = new LinkedHashMap<>();
        int recoveries, taskReads, detailReads, queueReads;
        Runnable beforeTransition;

        DownloadItem add(String id, DownloadStatus state, File destination) {
            DownloadItem row = new DownloadItem();
            row.setId(id);
            row.setUrl("https://example.test/fixture");
            row.setStatus(state);
            row.setDestinationPath(destination.getPath());
            row.setFileName(destination.getName());
            rows.put(id, row);
            return row;
        }

        @Override public synchronized int recoverInterrupted() {
            recoveries++;
            int changed = 0;
            for (DownloadItem row : rows.values()) {
                if (row.getStatus().isTransferring()) {
                    changed++;
                    row.setStatus(PENDING);
                    row.setAttemptToken(null);
                    row.setErrorMessage(null);
                }
            }
            return changed;
        }

        @Override public synchronized DownloadItem getDownload(String id) {
            taskReads++;
            DownloadItem row = rows.get(id);
            if (row == null) return null;
            return copy(row, false);
        }

        @Override public synchronized List<DownloadSegment> getSegments(String id) {
            detailReads++;
            DownloadItem row = rows.get(id);
            return row == null ? Collections.emptyList() : new ArrayList<>(row.getSegments());
        }

        @Override public synchronized List<DownloadItem> getDownloadsIn(DownloadStatus... states) {
            queueReads++;
            return rows.values().stream().filter(row -> Arrays.asList(states).contains(row.getStatus()))
                    .map(row -> copy(row, false)).collect(Collectors.toList());
        }

        @Override public synchronized boolean transition(DownloadItem expected, DownloadStatus next, String reason) {
            Runnable hook = beforeTransition;
            beforeTransition = null;
            if (hook != null) hook.run();
            DownloadItem row = matchingRow(expected, expected.getStatus());
            if (row == null) return false;
            row.setStatus(next);
            row.setErrorMessage(reason);
            return true;
        }

        @Override public synchronized boolean claim(DownloadItem item, File requested, String token) throws IOException {
            DownloadItem row = matchingRow(item, PENDING);
            if (row == null) return false;
            DownloadDestination.requireVacant(requested);
            for (DownloadItem updated : List.of(item, row)) {
                updated.setAttemptToken(token);
                updated.setStatus(CONNECTING);
                updated.setErrorMessage(null);
            }
            return true;
        }

        @Override public synchronized boolean saveRetry(DownloadItem replacement, DownloadStatus previous) {
            if (matchingRow(replacement, previous) == null) return false;
            DownloadItem updated = copy(replacement, true);
            updated.setStatus(PENDING);
            updated.setAttemptToken(null);
            updated.setErrorMessage(null);
            rows.put(updated.getId(), updated);
            return true;
        }

        @Override public synchronized boolean deleteDownload(DownloadItem expected, boolean deleteFile) {
            return matchingRow(expected, expected.getStatus()) != null && rows.remove(expected.getId()) != null;
        }

        @Override public synchronized int clearCompletedExcept(Set<String> owned) {
            int before = rows.size();
            rows.values().removeIf(row -> row.getStatus() == COMPLETED && !owned.contains(row.getId()));
            return before - rows.size();
        }

        synchronized boolean publish(DownloadItem attempt) {
            DownloadItem row = matchingRow(attempt, RUNNING);
            if (row == null) return false;
            row.setStatus(COMPLETED);
            row.setTempPath(null);
            return true;
        }

        synchronized boolean workerStatus(DownloadItem attempt, DownloadStatus state, String reason) {
            DownloadItem row = rows.get(attempt.getId());
            if (row == null || !row.getStatus().isTransferring() || !Objects.equals(row.getAttemptToken(), attempt.getAttemptToken())) return false;
            row.setStatus(state);
            row.setErrorMessage(reason);
            return true;
        }

        private DownloadItem matchingRow(DownloadItem expected, DownloadStatus expectedState) {
            DownloadItem row = rows.get(expected.getId());
            return row != null && row.getStatus() == expectedState && Objects.equals(row.getAttemptToken(), expected.getAttemptToken()) ? row : null;
        }

        private static DownloadItem copy(DownloadItem from, boolean segments) {
            DownloadItem to = new DownloadItem();
            to.setId(from.getId());
            to.setUrl(from.getUrl());
            to.setAttemptToken(from.getAttemptToken());
            to.setStatus(from.getStatus());
            to.setFileName(from.getFileName());
            to.setDestinationPath(from.getDestinationPath());
            to.setTempPath(from.getTempPath());
            to.setErrorMessage(from.getErrorMessage());
            to.setDownloadedBytes(from.getDownloadedBytes());
            to.setSpeedBytesPerSec(from.getSpeedBytesPerSec());
            to.setTotalBytes(from.getTotalBytes());
            to.setFullDownloadOnly(from.isFullDownloadOnly());
            if (segments) to.setSegments(new ArrayList<>(from.getSegments()));
            to.setWifiOnly(from.isWifiOnly());
            return to;
        }
    }
}
