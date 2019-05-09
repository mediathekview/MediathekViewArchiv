/*
 * MediathekView
 * Copyright (C) 2014 W. Xaver
 * W.Xaver[at]googlemail.com
 * http://zdfmediathk.sourceforge.net/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package mediathek.controller.starter;

import com.google.common.util.concurrent.RateLimiter;
import mSearch.tool.ApplicationConfiguration;
import mSearch.tool.MVHttpClient;
import mediathek.MediathekGui;
import mediathek.config.Daten;
import mediathek.config.MVConfig;
import mediathek.controller.MVBandwidthCountingInputStream;
import mediathek.controller.ThrottlingInputStream;
import mediathek.daten.DatenDownload;
import mediathek.gui.dialog.DialogContinueDownload;
import mediathek.gui.dialog.MeldungDownloadfehler;
import mediathek.gui.messages.*;
import mediathek.tool.MVInfoFile;
import mediathek.tool.MVSubtitle;
import net.engio.mbassy.bus.MBassador;
import net.engio.mbassy.listener.Handler;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;


public class DirectHttpDownload extends Thread {

    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final Logger logger = LogManager.getLogger(DirectHttpDownload.class);
    private final Daten daten;
    private final DatenDownload datenDownload;
    private final Start start;
    private final java.util.Timer bandwidthCalculationTimer;
    /**
     * The number of times how often the system should try a restart of the download.
     */
    private final int maxRestarts;
    /**
     * Instance which will limit the download speed
     */
    private final RateLimiter rateLimiter;
    private final MBassador<BaseEvent> messageBus;
    private final OkHttpClient httpClient;
    private HttpDownloadState state = HttpDownloadState.DOWNLOAD;
    /**
     * number of bytes already downloaded.
     * 0 if nothing has been downloaded before.
     */
    private long alreadyDownloaded = 0;
    private File file = null;
    private boolean retAbbrechen;
    private boolean dialogAbbrechenIsVis;
    private CompletableFuture<Void> infoFuture = null;
    private CompletableFuture<Void> subtitleFuture = null;

    public DirectHttpDownload(Daten daten, DatenDownload d, java.util.Timer bandwidthCalculationTimer) {
        super();

        httpClient = MVHttpClient.getInstance().getHttpClient();
        rateLimiter = RateLimiter.create(getDownloadLimit());
        messageBus = daten.getMessageBus();
        messageBus.subscribe(this);

        maxRestarts = MVConfig.getInt(MVConfig.Configs.SYSTEM_PARAMETER_DOWNLOAD_MAX_RESTART);

        this.daten = daten;
        this.bandwidthCalculationTimer = bandwidthCalculationTimer;
        datenDownload = d;
        start = datenDownload.start;
        setName("DIRECT DL THREAD_" + d.arr[DatenDownload.DOWNLOAD_TITEL]);

        start.status = Start.STATUS_RUN;
        StarterClass.notifyStartEvent(datenDownload);
    }

    /**
     * Handles the rate limit change launched somewhere in the UI
     *
     * @param evt the new limit
     */
    @Handler
    private void handleRateLimitChanged(DownloadRateLimitChangedEvent evt) {
        final long limit = calculateDownloadLimit(evt.newLimit);
        logger.info("thread changing download speed limit to {} KB", limit);
        rateLimiter.setRate(limit);
    }

    private long calculateDownloadLimit(long limit) {
        long newLimit;

        if (limit <= 0)
            newLimit = 10 * FileUtils.ONE_GB;
        else
            newLimit = limit * FileUtils.ONE_KB;

        return newLimit;
    }

    /**
     * Try to read the download limit from config file, other set to artificial limit 1GB/s!
     *
     * @return the limit in KB/s
     */
    private long getDownloadLimit() {
        final long downloadLimit = ApplicationConfiguration.getConfiguration().getLong(ApplicationConfiguration.DOWNLOAD_RATE_LIMIT, 0);
        return calculateDownloadLimit(downloadLimit);
    }

    /**
     * Return the content length of the requested Url.
     *
     * @param url {@link java.net.URL} to the specified content.
     * @return Length in bytes or -1 on error.
     */
    private long getContentLength(final URL url) throws IOException {
        long contentSize = -1;

        final Request request = new Request.Builder().url(url).get()
                .header("User-Agent", getUserAgent())
                .build();

        try (Response response = MVHttpClient.getInstance().getReducedTimeOutClient().newCall(request).execute();
             ResponseBody body = response.body()) {
            if (response.isSuccessful() && body != null) {
                contentSize = body.contentLength();
                // alles unter 300k sind Playlisten, ...
                if (contentSize < 300_000) {
                    contentSize = -1;
                }
            }
        }

        return contentSize;
    }

    private String getUserAgent() {
        return ApplicationConfiguration.getConfiguration().getString(ApplicationConfiguration.APPLICATION_USER_AGENT);
    }

    private void startInfoFileDownload() {
        final boolean downloadInfoFile = Boolean.parseBoolean(datenDownload.arr[DatenDownload.DOWNLOAD_INFODATEI]);
        if (downloadInfoFile) {
            infoFuture = CompletableFuture.runAsync(() -> {
                MVInfoFile infoFile = new MVInfoFile();
                infoFile.writeInfoFile(datenDownload);
            });
        }
    }

    private void downloadSubtitleFile() {
        if (Boolean.parseBoolean(datenDownload.arr[DatenDownload.DOWNLOAD_SUBTITLE])) {
            subtitleFuture = CompletableFuture.runAsync(() -> {
                MVSubtitle subtitleFile = new MVSubtitle();
                subtitleFile.writeSubtitle(datenDownload);
            });
        }
    }

    /**
     * Start the actual download process here.
     *
     * @throws IOException the io errors that may occur.
     */
    private void downloadContent(InputStream inputStream) throws IOException {
        startInfoFileDownload();

        downloadSubtitleFile();

        datenDownload.interruptRestart();

        /*
        This is a read-only property. Only experienced users should change it.
        Therefore we don´t save it.
         */
        final int bufferSize = ApplicationConfiguration.getConfiguration().getInt(ApplicationConfiguration.APPLICATION_HTTP_DOWNLOAD_FILE_BUFFER_SIZE, 64 * 1024);

        try (FileOutputStream fos = new FileOutputStream(file, (alreadyDownloaded != 0));
             BufferedOutputStream bos = new BufferedOutputStream(fos, bufferSize);
             ThrottlingInputStream tis = new ThrottlingInputStream(inputStream, rateLimiter);
             MVBandwidthCountingInputStream mvis = new MVBandwidthCountingInputStream(tis, bandwidthCalculationTimer)) {
            start.mVBandwidthCountingInputStream = mvis;
            datenDownload.mVFilmSize.addAktSize(alreadyDownloaded);
            final byte[] buffer = new byte[1024];
            long p, pp = 0, startProzent = -1;
            int len;
            long aktBandwidth, aktSize = 0;
            boolean melden = false;

            while ((len = start.mVBandwidthCountingInputStream.read(buffer)) != -1 && (!start.stoppen)) {
                alreadyDownloaded += len;
                bos.write(buffer, 0, len);
                datenDownload.mVFilmSize.addAktSize(len);

                //für die Anzeige prüfen ob sich was geändert hat
                if (aktSize != datenDownload.mVFilmSize.getAktSize()) {
                    aktSize = datenDownload.mVFilmSize.getAktSize();
                    melden = true;
                }
                if (datenDownload.mVFilmSize.getSize() > 0) {
                    p = (aktSize * (long) 1000) / datenDownload.mVFilmSize.getSize();
                    if (startProzent == -1) {
                        startProzent = p;
                    }
                    // p muss zwischen 1 und 999 liegen
                    if (p == 0) {
                        p = Start.PROGRESS_GESTARTET;
                    } else if (p >= 1000) {
                        p = 999;
                    }
                    start.percent = (int) p;
                    if (p != pp) {
                        pp = p;
                        // Restzeit ermitteln
                        if (p > 2 && p > startProzent) {
                            // sonst macht es noch keinen Sinn
                            final int diffZeit = start.startZeit.diffInSekunden();
                            final int restProzent = 1000 - (int) p;
                            start.restSekunden = (diffZeit * restProzent / (p - startProzent));
                            // anfangen zum Schauen kann man, wenn die Restzeit kürzer ist
                            // als die bereits geladene Speilzeit des Films
                            bereitsAnschauen(datenDownload);
                        }
                        melden = true;
                    }
                }
                aktBandwidth = start.mVBandwidthCountingInputStream.getBandwidth(); // bytes per second
                if (aktBandwidth != start.bandbreite) {
                    start.bandbreite = aktBandwidth;
                    melden = true;
                }
                if (melden) {
                    daten.getMessageBus().publishAsync(new DownloadProgressChangedEvent());
                    melden = false;
                }
            }
        }

        start.bandbreite = start.mVBandwidthCountingInputStream.getSumBandwidth();
        if (!start.stoppen) {
            if (datenDownload.quelle == DatenDownload.QUELLE_BUTTON) {
                // direkter Start mit dem Button
                start.status = Start.STATUS_FERTIG;
            } else if (StarterClass.pruefen(daten, datenDownload, start)) {
                //Anzeige ändern - fertig
                start.status = Start.STATUS_FERTIG;
            } else {
                //Anzeige ändern - bei Fehler fehlt der Eintrag
                start.status = Start.STATUS_ERR;
            }
        }
    }

    private void printHttpErrorMessage(Response response) {
        final String responseCode = "Responsecode: " + response.code() + '\n' + response.message();
        logger.error("HTTP-Fehler: {} {}", response.code(), response.message());

        if (!(start.countRestarted < maxRestarts)) {
            SwingUtilities.invokeLater(() -> new MeldungDownloadfehler(MediathekGui.ui(), "URL des Films:\n"
                    + datenDownload.arr[DatenDownload.DOWNLOAD_URL] + "\n\n"
                    + responseCode + '\n', datenDownload).setVisible(true));
        }

        state = HttpDownloadState.ERROR;
        start.status = Start.STATUS_ERR;
    }

    private Request buildDownloadRequest(@NotNull URL url) {
        var request = new Request.Builder().url(url).get()
                .header("User-Agent", getUserAgent());
        if (alreadyDownloaded != 0)
            request.header("Range", "bytes=" + alreadyDownloaded + '-');

        return request.build();
    }

    @Override
    public synchronized void run() {
        StarterClass.startmeldung(datenDownload, start);

        messageBus.publishAsync(new DownloadStartEvent());

        Response response = null;
        ResponseBody body = null;
        try {
            createDirectory();
            file = new File(datenDownload.arr[DatenDownload.DOWNLOAD_ZIEL_PFAD_DATEINAME]);

            if (!cancelDownload()) {
                final URL url = new URL(datenDownload.arr[DatenDownload.DOWNLOAD_URL]);
                datenDownload.mVFilmSize.setSize(getContentLength(url));
                datenDownload.mVFilmSize.setAktSize(0);

                Request request = buildDownloadRequest(url);
                response = httpClient.newCall(request).execute();
                body = response.body();
                if (response.isSuccessful() && body != null) {
                    downloadContent(body.byteStream());
                } else {
                    if (response.code() == HTTP_RANGE_NOT_SATISFIABLE) {
                        //close old stuff first
                        if (body != null)
                            body.close();
                        response.close();

                        //reset download count
                        alreadyDownloaded = 0;
                        request = buildDownloadRequest(url);
                        response = httpClient.newCall(request).execute();
                        body = response.body();
                        if (response.isSuccessful() && body != null)
                            downloadContent(body.byteStream());
                        else {
                            printHttpErrorMessage(response);
                        }
                    } else {
                        printHttpErrorMessage(response);
                    }
                }
            }
        } catch (IOException ex) {
            logger.error("run()", ex);
            start.status = Start.STATUS_ERR;
            state = HttpDownloadState.ERROR;
            SwingUtilities.invokeLater(() -> new MeldungDownloadfehler(MediathekGui.ui(), ex.getLocalizedMessage(), datenDownload).setVisible(true));
        } finally {
            if (body != null)
                body.close();

            if (response != null)
                response.close();
        }

        waitForPendingDownloads();

        StarterClass.finalizeDownload(datenDownload, start, state);

        messageBus.publishAsync(new DownloadFinishedEvent());
        messageBus.unsubscribe(this);
    }

    private void waitForPendingDownloads() {
        try {
            if (infoFuture != null)
                infoFuture.get();

            if (subtitleFuture != null)
                subtitleFuture.get();

        } catch (InterruptedException | ExecutionException e) {
            logger.error("waitForPendingDownloads().", e);
        }
    }

    private boolean cancelDownload() {
        if (!file.exists()) {
            // dann ist alles OK
            return false;
        }

        dialogAbbrechenIsVis = true;
        retAbbrechen = true;
        if (SwingUtilities.isEventDispatchThread()) {
            retAbbrechen = abbrechen_();
        } else {
            SwingUtilities.invokeLater(() -> {
                retAbbrechen = abbrechen_();
                dialogAbbrechenIsVis = false;
            });
        }
        while (dialogAbbrechenIsVis) {
            try {
                wait(100);
            } catch (Exception ignored) {

            }
        }
        return retAbbrechen;
    }

    private void createDirectory() {
        try {
            Files.createDirectories(Paths.get(datenDownload.arr[DatenDownload.DOWNLOAD_ZIEL_PFAD]));
        } catch (IOException ignored) {
        }
    }

    private boolean abbrechen_() {
        boolean result = false;
        if (file.exists()) {
            DialogContinueDownload dialogContinueDownload = new DialogContinueDownload(MediathekGui.ui(), datenDownload, true /*weiterführen*/);
            dialogContinueDownload.setVisible(true);

            switch (dialogContinueDownload.getResult()) {
                case CANCELLED:
                    // dann wars das
                    state = HttpDownloadState.CANCEL;
                    result = true;
                    break;

                case CONTINUE:
                    alreadyDownloaded = file.length();
                    break;

                case RESTART_WITH_NEW_NAME:
                    if (dialogContinueDownload.isNewName()) {
                        daten.getMessageBus().publishAsync(new DownloadListChangedEvent());
                        createDirectory();
                        file = new File(datenDownload.arr[DatenDownload.DOWNLOAD_ZIEL_PFAD_DATEINAME]);
                    }
                    break;
            }
        }
        return result;
    }

    private void bereitsAnschauen(DatenDownload datenDownload) {
        if (datenDownload.film != null && datenDownload.start != null) {
            final long filmLength = datenDownload.film.getFilmLength();
            if (filmLength > 0
                    && datenDownload.start.restSekunden > 0
                    && datenDownload.mVFilmSize.getAktSize() > 0
                    && datenDownload.mVFilmSize.getSize() > 0) {
                // macht nur dann Sinn
                final long zeitGeladen = filmLength * datenDownload.mVFilmSize.getAktSize() / datenDownload.mVFilmSize.getSize();
                if (zeitGeladen > (datenDownload.start.restSekunden * 1.1 /* plus 10% zur Sicherheit*/)) {
                    datenDownload.start.beginnAnschauen = true;
                }
            }
        }
    }

    enum HttpDownloadState {

        CANCEL, ERROR, DOWNLOAD
    }
}
