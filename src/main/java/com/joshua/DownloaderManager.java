package com.joshua;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class DownloaderManager {

    private static final int CONNECT_TIMEOUT_MS = 15000; // 15 seconds
    private static final int READ_TIMEOUT_MS = 15000;    // 15 seconds
    private static final String TEMP_FILE_SUFFIX = ".part_dl";
    private static final String FINAL_DOWNLOAD_SUFFIX = ".dl_tmp"; // Temporary name for the final merged file

    private final String fileURL;
    private final String outputFileName;
    private final int numberOfThreadsConfigured; // Max threads user wants
    private volatile long maxSpeedLimitBps;       // Bytes per second (non-final and volatile)
    private final ProgressListener listener;

    private volatile boolean paused = false;
    private final Object pauseLock = new Object();
    private DownloadThread[] activeThreads; // To manage active threads

    /**
     * Constructs a DownloaderManager.
     * @param fileURL URL of the file to download.
     * @param outputFileName Desired name for the downloaded file.
     * @param numberOfThreads Number of threads to use for downloading.
     * @param listener Callback for progress updates.
     * @param maxSpeedLimitBps Overall maximum download speed in Bytes per second (0 for no limit).
     */
    public DownloaderManager(String fileURL, String outputFileName, int numberOfThreads, ProgressListener listener, long maxSpeedLimitBps) {
        this.fileURL = fileURL;
        this.outputFileName = outputFileName;
        this.numberOfThreadsConfigured = Math.max(1, numberOfThreads); // Ensure at least 1 thread
        this.listener = listener;
        this.maxSpeedLimitBps = maxSpeedLimitBps; // Initial speed limit
    }

    public void pause() {
        paused = true;
        System.out.println("Download pause requested.");
    }

    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
            System.out.println("Download resume requested.");
        }
    }

    /**
     * Updates the maximum download speed limit for the currently active download.
     * This change is propagated to all active download threads.
     *
     * @param newMaxSpeedLimitBps The new maximum speed limit in Bytes per second.
     */
    public synchronized void updateMaxSpeedLimit(long newMaxSpeedLimitBps) {
        System.out.println("DM: Updating max speed limit to: " + newMaxSpeedLimitBps + " B/s");
        this.maxSpeedLimitBps = newMaxSpeedLimitBps;

        if (activeThreads != null && activeThreads.length > 0) {
            long speedLimitPerThread = (this.maxSpeedLimitBps > 0 && activeThreads.length > 0)
                                     ? this.maxSpeedLimitBps / activeThreads.length
                                     : 0; // 0 means no limit for the thread

            for (DownloadThread thread : activeThreads) {
                if (thread != null && thread.isAlive()) {
                    thread.updateSpeedLimit(speedLimitPerThread);
                }
            }
            System.out.println("DM: Propagated speed limit per thread: " + speedLimitPerThread + " B/s");
        }
    }


    public void download() throws Exception {
        HttpURLConnection connection = null;
        long fileSize;

        try {
            URL url = new URL(fileURL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("HEAD"); // Get headers, including file size
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");


            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_ACCEPTED) {
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == 307 || responseCode == 308) {
                    String newUrl = connection.getHeaderField("Location");
                    connection.disconnect();
                    System.out.println("Redirecting to: " + newUrl);
                    URL redirectedUrl = new URL(newUrl); // Ensure newUrl is a full URL
                    connection = (HttpURLConnection) redirectedUrl.openConnection();
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setRequestMethod("HEAD");
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0"); // Repeat User-Agent
                    responseCode = connection.getResponseCode();
                     if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_ACCEPTED) {
                         throw new IOException("Server replied with HTTP code: " + responseCode + " after redirect.");
                     }
                } else {
                    throw new IOException("Server replied with HTTP code: " + responseCode);
                }
            }

            fileSize = connection.getContentLengthLong();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (fileSize <= 0) {
            if (fileSize == 0) { // Handle 0-byte file
                System.out.println("File size is 0 bytes. Creating empty file.");
                try (FileOutputStream fos = new FileOutputStream(outputFileName)) {
                    // Empty file created
                }
                if (listener != null) {
                    listener.onProgress(0, 0, 0); // Report 0 downloaded, 0 total, 0 speed
                }
                System.out.println("✅ Download complete (0-byte file). File saved as: " + outputFileName);
                return; // Successfully "downloaded" 0-byte file
            }
            throw new IOException("Invalid file size (" + fileSize + ") or server did not provide content length.");
        }

        System.out.println("Total file size: " + fileSize + " bytes");

        List<DownloadThread> threadList = new ArrayList<>();
        long bytesAlreadyAssigned = 0;
        int actualNumberOfThreads = (int) Math.min(this.numberOfThreadsConfigured, fileSize); // Max 1 thread per byte
        if (actualNumberOfThreads > 1 && fileSize / actualNumberOfThreads < (1024 * 50) ) { // If parts are less than 50KB, reduce threads
            actualNumberOfThreads = (int) Math.max(1, fileSize / (1024*50)); // Aim for at least 50KB parts if possible
            actualNumberOfThreads = Math.max(1, actualNumberOfThreads); // ensure at least 1
            System.out.println("Adjusted number of threads to " + actualNumberOfThreads + " due to small part sizes.");
        }


        long bytesPerThread = fileSize / actualNumberOfThreads;
        long remainingBytes = fileSize % actualNumberOfThreads; // Distribute remainder

        for (int i = 0; i < actualNumberOfThreads; i++) {
            long startByte = bytesAlreadyAssigned;
            long partSizeForThread = bytesPerThread;
            if (remainingBytes > 0) {
                partSizeForThread++;
                remainingBytes--;
            }
            long endByte = startByte + partSizeForThread - 1;

            if (startByte > endByte || startByte >= fileSize) {
                continue;
            }
            
            endByte = Math.min(endByte, fileSize -1);


            // Initial speed limit per thread
            long initialSpeedLimitPerThread = (maxSpeedLimitBps > 0 && actualNumberOfThreads > 0)
                                            ? maxSpeedLimitBps / actualNumberOfThreads
                                            : 0;
            DownloadThread dt = new DownloadThread(fileURL, startByte, endByte, i, initialSpeedLimitPerThread, pauseLock, () -> paused, outputFileName);
            threadList.add(dt);
            bytesAlreadyAssigned = endByte + 1;
        }
        
        if (threadList.isEmpty() && fileSize > 0) {
            System.out.println("Warning: Thread distribution resulted in no threads. Falling back to single thread for small file.");
            DownloadThread dt = new DownloadThread(fileURL, 0, fileSize - 1, 0, maxSpeedLimitBps, pauseLock, () -> paused, outputFileName);
            threadList.add(dt);
        }


        activeThreads = threadList.toArray(new DownloadThread[0]);
        if (activeThreads.length == 0 && fileSize > 0) {
             throw new IOException("Failed to create any download threads for a non-zero size file.");
        }


        for (DownloadThread thread : activeThreads) {
            thread.start();
        }

        boolean allThreadsDone = false;
        long[] lastReportedDownloadedBytesPerThread = new long[activeThreads.length];
        long lastProgressUpdateTime = System.currentTimeMillis();

        while (!allThreadsDone) {
            if (paused) { // Main loop respects pause too, reduces activity.
                synchronized(pauseLock) {
                    while(paused) {
                        pauseLock.wait(1000); // Wait with timeout to allow periodic checks
                    }
                }
            }

            Thread.sleep(500); // Update interval for progress

            long currentTotalDownloaded = 0;
            long currentBytesDownloadedThisInterval = 0;
            allThreadsDone = true; // Assume done until a running thread is found

            for (int i = 0; i < activeThreads.length; i++) {
                DownloadThread thread = activeThreads[i];
                long threadDownloaded = thread.getDownloadedBytes();
                currentTotalDownloaded += threadDownloaded;
                
                currentBytesDownloadedThisInterval += (threadDownloaded - lastReportedDownloadedBytesPerThread[i]);
                lastReportedDownloadedBytesPerThread[i] = threadDownloaded;


                if (thread.isAlive()) {
                    allThreadsDone = false;
                } else if (!thread.isSuccess()) {
                    // A thread died without success
                    cleanupTemporaryFiles(activeThreads);
                    throw new IOException("Download failed: Thread " + thread.getThreadId() + " encountered an error: " +
                                          (thread.getError() != null ? thread.getError().getMessage() : "Unknown error"));
                }
            }

            long currentTime = System.currentTimeMillis();
            long intervalMillis = currentTime - lastProgressUpdateTime;
            
            long speedBps = 0;
            if (intervalMillis > 0) {
                 speedBps = (currentBytesDownloadedThisInterval * 1000) / intervalMillis;
            }
            // If overall speed limit is set, cap the reported/used speed
             if (this.maxSpeedLimitBps > 0) { // Uses the (potentially updated) volatile field
                speedBps = Math.min(speedBps, this.maxSpeedLimitBps);
            }


            if (listener != null) {
                listener.onProgress(currentTotalDownloaded, fileSize, speedBps);
            }
            
            lastProgressUpdateTime = currentTime;


            if (currentTotalDownloaded >= fileSize) { // Early exit if all bytes accounted for
                allThreadsDone = true;
            }
        }

        // Final check for thread success after loop
        for (DownloadThread thread : activeThreads) {
            if (!thread.isSuccess()) {
                cleanupTemporaryFiles(activeThreads);
                throw new IOException("Download failed post-loop: Thread " + thread.getThreadId() + " did not complete successfully. " +
                                      (thread.getError() != null ? thread.getError().getMessage() : "Incomplete."));
            }
        }

        System.out.println("All parts downloaded. Merging files...");
        mergeParts(activeThreads, fileSize);
        System.out.println("✅ Download complete. File saved as: " + outputFileName);
        if (listener != null) { // Ensure final progress update
            listener.onProgress(fileSize, fileSize, 0);
        }
    }

    private void mergeParts(DownloadThread[] threads, long totalFileSize) throws IOException {
        String tempFinalFileName = outputFileName + FINAL_DOWNLOAD_SUFFIX;
        File tempFinalFile = new File(tempFinalFileName);
        File finalFile = new File(outputFileName);

        try (FileOutputStream fos = new FileOutputStream(tempFinalFile)) {
            long totalBytesWritten = 0;
            for (DownloadThread thread : threads) {
                File partFile = new File(thread.getTempFileName());
                if (!partFile.exists()) {
                    throw new IOException("Temporary part file missing: " + partFile.getName());
                }
                try (FileInputStream fis = new FileInputStream(partFile)) {
                    byte[] buffer = new byte[8192]; // Increased buffer size
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalBytesWritten += bytesRead;
                    }
                }
            }
            if (totalBytesWritten != totalFileSize) {
                 System.err.println("Warning: Merged file size (" + totalBytesWritten + ") does not match expected file size (" + totalFileSize + ").");
            }

        } catch (IOException e) {
            tempFinalFile.delete(); // Attempt to delete partially merged file on error
            throw e; // Re-throw to be caught by UI
        } finally {
            cleanupTemporaryFiles(threads);
        }

        if (finalFile.exists()) {
            if (!finalFile.delete()) {
                System.err.println("Warning: Could not delete existing file: " + finalFile.getName());
            }
        }
        if (!tempFinalFile.renameTo(finalFile)) {
            throw new IOException("Could not rename temporary file " + tempFinalFile.getName() + " to " + finalFile.getName());
        }
    }

    private void cleanupTemporaryFiles(DownloadThread[] threads) {
        if (threads == null) return;
        for (DownloadThread thread : threads) {
            if (thread != null) {
                File partFile = new File(thread.getTempFileName());
                if (partFile.exists()) {
                    if (!partFile.delete()) {
                        System.err.println("Warning: Could not delete temporary file: " + partFile.getName());
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalSize, long speedBps); // Speed in Bytes Per Second
    }

    // Inner DownloadThread class
    private static class DownloadThread extends Thread {
        private final String fileURL;
        private final long startByte;
        private final long endByte;
        private final int threadId;
        private final Object pauseLock;
        private final PauseChecker pauseChecker;
        private final String baseOutputFileName; // Used for temp file naming


        private volatile long speedLimitBpsPerThread; // Made non-final and volatile
        private volatile long downloadedBytes = 0;
        private volatile boolean success = false;
        private volatile Exception error = null;

        interface PauseChecker {
            boolean isPaused();
        }

        public DownloadThread(String fileURL, long startByte, long endByte, int threadId,
                              long initialSpeedLimitBpsPerThread, Object pauseLock, PauseChecker pauseChecker, String baseOutputFileName) {
            this.fileURL = fileURL;
            this.startByte = startByte;
            this.endByte = endByte;
            this.threadId = threadId;
            this.speedLimitBpsPerThread = initialSpeedLimitBpsPerThread; // Set initial limit
            this.pauseLock = pauseLock;
            this.pauseChecker = pauseChecker;
            this.baseOutputFileName = baseOutputFileName;
            setName("DownloadThread-" + threadId);
        }

        public void updateSpeedLimit(long newSpeedLimitBpsPerThread) {
            this.speedLimitBpsPerThread = newSpeedLimitBpsPerThread;
        }

        public String getTempFileName() {
            return "part_" + threadId + "_" + new File(baseOutputFileName).getName() + TEMP_FILE_SUFFIX;
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public boolean isSuccess() {
            return success;
        }

        public Exception getError() {
            return error;
        }
        public int getThreadId() { return threadId; }


        @Override
        public void run() {
            HttpURLConnection connection = null;
            RandomAccessFile output = null;

            try {
                File tempFile = new File(getTempFileName());
                output = new RandomAccessFile(tempFile, "rw");

                URL url = new URL(fileURL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Range", "bytes=" + startByte + "-" + endByte);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");

                int responseCode = connection.getResponseCode();
                 if (responseCode != HttpURLConnection.HTTP_PARTIAL && responseCode != HttpURLConnection.HTTP_OK) {
                    if (responseCode == HttpURLConnection.HTTP_OK && startByte == 0 && connection.getContentLengthLong() == (endByte - startByte + 1) ) {
                        System.out.println("Thread " + threadId + ": Server sent full content instead of range, but it matches. Proceeding.");
                    } else {
                        throw new IOException("Thread " + threadId + " server error: " + responseCode +
                                              " for range " + startByte + "-" + endByte);
                    }
                }


                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;

                    long currentSecondStartTime = System.currentTimeMillis();
                    long bytesDownloadedThisSecond = 0;

                    while ((bytesRead = input.read(buffer)) != -1) {

                        synchronized (pauseLock) {
                            while (pauseChecker.isPaused()) {
                                pauseLock.wait();
                            }
                        }

                        output.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;
                        bytesDownloadedThisSecond += bytesRead;

                        if (speedLimitBpsPerThread > 0) {
                            long elapsedMillisThisSecond = System.currentTimeMillis() - currentSecondStartTime;
                            if (elapsedMillisThisSecond < 1000) { // Still within the current second
                                long expectedMillis = (bytesDownloadedThisSecond * 1000L) / speedLimitBpsPerThread;
                                if (elapsedMillisThisSecond < expectedMillis) {
                                    Thread.sleep(expectedMillis - elapsedMillisThisSecond);
                                }
                            } else { // Second has passed
                                currentSecondStartTime = System.currentTimeMillis(); // Reset for next second
                                bytesDownloadedThisSecond = 0;
                            }
                        }
                        if (downloadedBytes > (endByte - startByte + 1)) {
                            System.err.println("Thread " + threadId + ": Downloaded " + downloadedBytes + " but expected " + (endByte - startByte + 1) + ". Trimming.");
                            downloadedBytes = (endByte - startByte + 1);
                            break; 
                        }
                    }
                }
                if (downloadedBytes == (this.endByte - this.startByte + 1)) {
                    success = true;
                } else if (pauseChecker.isPaused()) { // If paused, incomplete download is expected
                    System.out.println("Thread " + threadId + " paused. Downloaded " + downloadedBytes + " of " + (this.endByte - this.startByte + 1) + " bytes.");
                    // Don't mark as error, but not success either until resumed and completed
                }
                else {
                    this.error = new IOException("Thread " + threadId + " did not download its complete part. Expected " +
                            (this.endByte - this.startByte + 1) + " but got " + downloadedBytes);
                    System.err.println(this.error.getMessage());
                }

            } catch (InterruptedException e) {
                this.error = e;
                System.err.println("Thread " + threadId + " interrupted: " + e.getMessage());
                Thread.currentThread().interrupt(); // Preserve interrupt status
            } catch (Exception e) {
                this.error = e;
                if (pauseChecker.isPaused() && (e instanceof java.net.SocketException || (e.getMessage() != null && e.getMessage().toLowerCase().contains("socket closed")))) {
                    System.out.println("Thread " + threadId + " network error during pause, likely benign: " + e.getMessage());
                } else {
                    System.err.println("Thread " + threadId + " general error: " + e.getMessage());
                    // e.printStackTrace(); // Uncomment for detailed stack trace
                }
            } finally {
                try {
                    if (output != null) output.close();
                } catch (IOException e) {
                    System.err.println("Thread " + threadId + " error closing output file: " + e.getMessage());
                }
                if (connection != null) {
                    connection.disconnect();
                }
                if (success) {
                     System.out.println("Thread " + threadId + " finished successfully. Downloaded " + downloadedBytes + " bytes.");
                } else if (error == null && !pauseChecker.isPaused() && downloadedBytes != (this.endByte - this.startByte + 1)) { // If no explicit error, not paused, but not all bytes downloaded
                    this.error = new IOException("Thread " + threadId + " finished without success and no specific error. Downloaded: " + downloadedBytes + "/" + (this.endByte - this.startByte + 1));
                    System.err.println(this.error.getMessage());
                }
            }
        }
    }
}