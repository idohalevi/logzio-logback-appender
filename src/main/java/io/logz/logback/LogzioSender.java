package io.logz.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bluejeans.common.bigqueue.BigQueue;
import io.logz.logback.exceptions.LogzioServerErrorException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class LogzioSender {


    private final int MAX_SIZE_IN_BYTES = 3 * 1024 * 1024;  // 3 MB
    private final int INITIAL_WAIT_BEFORE_RETRY_MS = 2000;
    private final int MAX_RETRIES_ATTEMPTS = 3;

    private final ScheduledThreadPoolExecutor tasksExecutor;

    private BigQueue logsBuffer;
    private File queueDirectory;
    private URL logzioListenerUrl;
    private HttpURLConnection conn;
    private boolean dontCheckEnoughDiskSpace = false;

    private String logzioToken;
    private String logzioType;
    private int drainTimeout;
    private int fsPercentThreshold;
    private String logzioUrl;
    private int socketTimeout;
    private int connectTimeout;
    private boolean debug;
    private LogzioLogbackAppender.StatusReporter reporter;

    private final static DateTimeFormatter formatter  = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZone(ZoneId.of("UTC"));

    public LogzioSender(String logzioToken, String logzioType, int drainTimeout, int fsPercentThreshold, String bufferDir,
                        String logzioUrl, int socketTimeout, int connectTimeout, boolean debug, LogzioLogbackAppender.StatusReporter reporter) throws IllegalArgumentException {
        try {
            this.logzioToken = logzioToken;
            this.logzioType = logzioType;
            this.drainTimeout = drainTimeout;
            this.fsPercentThreshold = fsPercentThreshold;
            this.logzioUrl = logzioUrl;
            this.socketTimeout = socketTimeout;
            this.connectTimeout = connectTimeout;
            this.debug = debug;
            this.reporter = reporter;

            if (this.fsPercentThreshold == -1) {
                dontCheckEnoughDiskSpace = true;
            }

            logsBuffer = new BigQueue(bufferDir, "logzio-logback-appender");
            queueDirectory = new File(bufferDir);

            logzioListenerUrl = new URL(this.logzioUrl + "/?token=" + this.logzioToken + "&type=" + this.logzioType);

        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("For some reason could not initialize URL. Cant recover..");
        }

        tasksExecutor = new ScheduledThreadPoolExecutor(1);
    }

    public void start() {
        tasksExecutor.scheduleWithFixedDelay(this::drainQueueAndSend, 0, drainTimeout, TimeUnit.SECONDS);
    }

    public void stop() {
        try {
            tasksExecutor.shutdown();
            tasksExecutor.awaitTermination(20, TimeUnit.SECONDS);
            tasksExecutor.shutdownNow();

            // Just want to make sure nothing left behind
            drainQueue();

        } catch (InterruptedException e) {

            // Reset the interrupt flag
            Thread.currentThread().interrupt();
        }
    }

    public void drainQueueAndSend() {
        try {
            drainQueue();

        } catch (Exception e) {
            // We cant throw anything out, or the task will stop, so just swallow all
            reporter.error("Uncaught error from Logz.io sender", e);
        }
    }

    public void send(ILoggingEvent message) {
        enqueue(formatMessage(message).getBytes());
    }

    private void enqueue(byte[] message) {
        if (isEnoughDiskSpace()) {
            logsBuffer.enqueue(message);
        }
    }

    private boolean isEnoughDiskSpace() {
        if (dontCheckEnoughDiskSpace) {
            return true;
        }

        int actualFsPercent = (int) (((double) queueDirectory.getUsableSpace() / queueDirectory.getTotalSpace()) * 100);
        if (actualFsPercent >= fsPercentThreshold) {

            reporter.warning(String.format("Logz.io: Dropping logs, as FS free usable space on %s is %d percent, and the drop threshold is %d percent",
                    queueDirectory.getAbsolutePath(), actualFsPercent, fsPercentThreshold));

            return false;
        }
        else {
            return true;
        }
    }

    private List dequeueUpToMaxBatchSize() {
        List<FormattedLogMessage> logsList = new ArrayList<FormattedLogMessage>();
        while (!logsBuffer.isEmpty()) {

            logsList.add(new FormattedLogMessage(logsBuffer.dequeue()));
            if (sizeInBytes(logsList) >= MAX_SIZE_IN_BYTES) {
                break;
            }
        }
        return logsList;
    }

    private void drainQueue() {
        if (!logsBuffer.isEmpty()) {
            while (!logsBuffer.isEmpty()) {

                List<FormattedLogMessage> logsList = dequeueUpToMaxBatchSize();

                try {
                    sendToLogzio(logsList);

                } catch (LogzioServerErrorException e) {
                    debug("Could not send log to logz.io: ", e);
                    debug("Will retry in the next interval");

                    // And lets return everything to the queue
                    logsList.forEach((logMessage) -> enqueue(logMessage.getMessage()));

                    // Lets wait for a new interval, something is wrong in the server side
                    break;
                }
            }
        }
    }

    private int sizeInBytes(List<FormattedLogMessage> logMessages) {
        int totalSize = 0;
        for (FormattedLogMessage currLog : logMessages) totalSize += currLog.getSize();

        return totalSize;
    }

    private byte[] toNewLineSeparatedByteArray(List<FormattedLogMessage> messages) {

        try {
            ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream(sizeInBytes(messages));
            for (FormattedLogMessage currMessage : messages) byteOutputStream.write(currMessage.getMessage());
            return byteOutputStream.toByteArray();
        }
        catch (IOException e) {

            throw new RuntimeException(e);
        }
    }

    private boolean shouldRetry(int statusCode) {

        boolean shouldRetry = true;

        switch (statusCode) {

            case HttpURLConnection.HTTP_OK:
            case HttpURLConnection.HTTP_BAD_REQUEST:
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                shouldRetry = false;
                break;
        }

        return shouldRetry;
    }

    private void sendToLogzio(List<FormattedLogMessage> messages) throws LogzioServerErrorException {
        try {

            byte[] payload = toNewLineSeparatedByteArray(messages);
            int currentRetrySleep = INITIAL_WAIT_BEFORE_RETRY_MS;

            for (int currTry = 1; currTry <= MAX_RETRIES_ATTEMPTS; currTry++) {

                boolean shouldRetry = true;
                int responseCode = 0;
                String responseMessage = "";
                IOException savedException = null;

                try {
                    conn = (HttpURLConnection) logzioListenerUrl.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-length", String.valueOf(payload.length));
                    conn.setRequestProperty("Content-Type", "text/plain");
                    conn.setReadTimeout(socketTimeout);
                    conn.setConnectTimeout(connectTimeout);
                    conn.setDoOutput(true);
                    conn.setDoInput(true);

                    conn.getOutputStream().write(payload);

                    responseCode = conn.getResponseCode();
                    responseMessage = conn.getResponseMessage();

                    if (responseCode == HttpURLConnection.HTTP_BAD_REQUEST) {
                        reporter.warning("Got 400 from logzio, here is the output: \n " + responseMessage);
                    }
                    if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                        reporter.error("Logz.io: Got forbidden! Your token is not right. Unfortunately, dropping logs. Message: " + responseMessage);
                    }

                    shouldRetry = shouldRetry(responseCode);
                }
                catch (IOException e) {
                    savedException = e;
                    debug("Got IO exception - " + e.getMessage());
                }

                if (!shouldRetry) {
                    debug("Successfully sent bulk to logz.io, size: " + payload.length);
                    break;

                } else {

                    if (currTry == MAX_RETRIES_ATTEMPTS) {

                        if (savedException != null) {

                            reporter.error("Got IO exception on the last bulk try to logz.io", savedException);
                        }
                        // Giving up, something is broken on Logz.io side, we will try again later
                        throw new LogzioServerErrorException("Got HTTP " + responseCode + " code from logz.io, with message: " + responseMessage);
                    }

                    debug("Could not send log to logz.io, retry (" + currTry + "/" + MAX_RETRIES_ATTEMPTS + ")");
                    debug("Sleeping for " + currentRetrySleep + " ms and will try again.");
                    Thread.sleep(currentRetrySleep);
                    currentRetrySleep *= 2;
                }
            }

        } catch (InterruptedException e) {
            debug("Got interrupted exception");
            Thread.currentThread().interrupt();
        }
    }

    private void debug(String message) {
        if (debug) {
            reporter.info("DEBUG: " + message);
        }
    }

    private void debug(String message, Throwable e) {
        if (debug) {
            reporter.info("DEBUG: " + message, e);
        }
    }

    private String formatMessage(ILoggingEvent loggingEvent) {
        Date timeStamp = new Date(loggingEvent.getTimeStamp());

        return String.format("{\"@timestamp\": \"%s\", \"loglevel\": \"%s\", \"message\": \"%s\", \"logger\": \"%s\", \"thread\": \"%s\"}\n",
                formatter.format(timeStamp.toInstant()), loggingEvent.getLevel().levelStr, loggingEvent.getFormattedMessage(), loggingEvent.getLoggerName(), loggingEvent.getThreadName());
    }
}