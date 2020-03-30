package de.gsi.acc.remote;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.gsi.chart.utils.FXUtils;
import de.gsi.chart.utils.WriteFxImage;
import de.gsi.dataset.event.EventListener;
import de.gsi.dataset.event.EventRateLimiter;
import de.gsi.dataset.event.UpdateEvent;

import io.javalin.http.sse.SseClient;
import io.javalin.http.util.RateLimit;

public class RestfullRemoteView implements EventListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(RestfullRemoteView.class);
    private static final String LAST_UPDATE = RestfullRemoteView.class.getCanonicalName() + ".LastServerUpdateMillis.";

    private final SnapshotParameters snapshotParameters = new SnapshotParameters();
    private final IntegerProperty width = new SimpleIntegerProperty(this, "width", 100);
    private final IntegerProperty height = new SimpleIntegerProperty(this, "height", 100);
    private final String exportName;
    private final Region regionToCapture;
    private WritableImage writableImage;
    private final long maxUpdatePeriod;
    private final Object imageBufferLock = new Object();
    private ByteBuffer imageByteBufferPrimary = ByteBuffer.allocate(10_000_000);
    private ByteBuffer imageByteBufferSecondary = ByteBuffer.allocate(10_000_000);
    private long imageByteBufferUpdateMillis;
    private final EventRateLimiter eventRateLimiter;

    public RestfullRemoteView(final String exportName, final Region regionToCapture, final long maxUpdatePeriod) {
        this.regionToCapture = regionToCapture;
        this.exportName = exportName;
        this.maxUpdatePeriod = maxUpdatePeriod;

        writableImage = new WritableImage((int) regionToCapture.getWidth(), (int) regionToCapture.getHeight());
        width.bind(regionToCapture.widthProperty());
        height.bind(regionToCapture.heightProperty());

        final EventListener snapshotListener = evt -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.atDebug().addArgument(evt).log("snapshotListener listener called by {}");
            }
            try {
                FXUtils.runAndWait(() -> writableImage = regionToCapture.snapshot(snapshotParameters, writableImage));

                new Thread(() -> {
                    // thread safe buffer copy
                    synchronized (imageBufferLock) {
                        imageByteBufferSecondary.clear();
                        imageByteBufferSecondary = WriteFxImage.encode(writableImage, imageByteBufferSecondary, true, Deflater.BEST_SPEED, null);
                        // thread safe swap of buffers
                        final ByteBuffer temp = imageByteBufferPrimary;
                        imageByteBufferPrimary = imageByteBufferSecondary;
                        imageByteBufferSecondary = temp;
                        imageByteBufferUpdateMillis = System.currentTimeMillis();
                        imageBufferLock.notifyAll();
                        for (SseClient client : RestServer.getEventClients(exportName + ".png")) {
                    client.sendEvent("PING " + imageByteBufferUpdateMillis);
                        }
                //System.err.println("############ new data ############ ");
            }
        }).start();
    }
    catch (InterruptedException | ExecutionException /* | IOException */ e) {
        if (LOGGER.isErrorEnabled()) {
            LOGGER.atError().setCause(e).log("snapshotListener -> Node::snapshot(..)");
        }
    }
};
eventRateLimiter = new EventRateLimiter(snapshotListener, maxUpdatePeriod);

RestServer.startRestServer(8080); // NOPMD - deliberately overridable
initDefaultRoutes(); // NOPMD - deliberately overridable

//initSeeEndpoint(); // NOPMD - deliberately overridable
}

public IntegerProperty getHeight() {
    return height;
}

public Region getRegionToCapture() {
    return regionToCapture;
}

public IntegerProperty getWidth() {
    return width;
}

public WritableImage getWritableImage() {
    return writableImage;
}

@Override
public void handle(UpdateEvent event) {
    eventRateLimiter.handle(event);
}

protected void initDefaultRoutes() {
    final String cookieNameImage = LAST_UPDATE + exportName + ".png";
    RestServer.registerEndpoint(exportName + ".png", ctx -> {
        // new RateLimit(ctx).requestPerTimeUnit(27, TimeUnit.SECONDS); // throws if rate limit is exceeded
        final String cookie = ctx.cookieMap().getOrDefault(cookieNameImage, "0");
        //System.err.println("invoked status by " + ctx.req.getRemoteAddr() + " cookieNameImage = " + cookie);

        long lastUpdate = 0;
        try {
            lastUpdate = Long.parseLong(cookie);
        } catch (NumberFormatException e) {
            // return to default
            LOGGER.atError().setCause(e);
            new RateLimit(ctx).requestPerTimeUnit(1, TimeUnit.SECONDS); // throws if rate limit is exceeded
        }

        ctx.contentType(MimeType.PNG.toString());
        RestServer.suppressCaching(ctx);

        final byte[] cached;
        try {
            // Calling wait() will block this thread until another thread
            // calls notify() on the object.

            synchronized (imageBufferLock) {
                if (imageByteBufferUpdateMillis <= lastUpdate) {
                    // final long diff = imageByteBufferUpdateMillis - lastUpdate;
                    imageBufferLock.wait();
                    // System.err.println("wait lastUpdate " + lastUpdate + " vs. imageByteBufferUpdateMillis " //
                    //     + imageByteBufferUpdateMillis + " diff = " + diff + " user " + ctx.req.getRemoteAddr()+":"+ctx.req.getRemotePort());
                }

                cached = Arrays.copyOf(imageByteBufferPrimary.array(), imageByteBufferPrimary.limit());
            }
            RestServer.addLongPollingCookie(ctx, cookieNameImage, imageByteBufferUpdateMillis);
            RestServer.writeBytesToContext(ctx, cached, cached.length);

        } catch (InterruptedException e) {
            // Happens if someone interrupts your thread.
            e.printStackTrace();
        }
    });

    final String cookieNameExportLandingPage = LAST_UPDATE + exportName;
    RestServer.registerEndpoint(exportName, ctx -> {
        ctx.res.setContentType(MimeType.HTML.toString());
        //RestServer.applyRateLimit(ctx, 5, TimeUnit.MINUTES);
        RestServer.suppressCaching(ctx);

        final long now = System.currentTimeMillis();
        System.err.println("invoked status by " + ctx.req.getRemoteAddr() + " with parameter = " //
                           + ctx.queryParam("updatePeriod", "10000") + " cookieNameExportLandingPage = " + ctx.cookie(cookieNameExportLandingPage) + " cookieNameImage =  " + ctx.cookie(cookieNameImage));

        String updatePeriodString = ctx.queryParam("updatePeriod", "10000");
        long updatePeriod = 500;
        if (updatePeriodString != null) {
            try {
                updatePeriod = Long.valueOf(updatePeriodString);
            } catch (Exception e) {
                if (LOGGER.isErrorEnabled()) {
                    final String clientIp = "";
                    LOGGER.atError().setCause(e).addArgument(updatePeriodString).addArgument(clientIp).log("could not parse 'updatePeriod'={} argument sent by client {}");
                }
            }
        }
        updatePeriod = Math.max(maxUpdatePeriod, updatePeriod);
        //            updatePeriod = Math.max(20, updatePeriod);
        StringBuilder builder = new StringBuilder();
        // clang-format off
            builder.append("<!DOCTYPE html>\n<html><head><script language=\"JavaScript\"><!--\n") //
                    .append("function refreshIt() {\n") //
                    .append("  if (!document.images) return;\n") //
                    .append("  document.images['myStatus'].src = '").append(exportName).append(".png?' + Math.random();\n") //
//                    .append("  document.images['myStatus'].src = '").append(exportName).append(".png';\n") //
                    .append("  setTimeout('refreshIt()', ").append(updatePeriod).append("); // refresh every n milliseconds\n") //
                    .append("}\n") //
                    .append("//--></script></head>\n") //
                    .append("<body onLoad=\" setTimeout('refreshIt()', ").append(updatePeriod).append(")\">\n") //
                    // .append("<img src=\"image.png\" name=\"myStatus\" width=\"100%\"
                    // height=\"100%\" border=\"0\">\n") //
                    .append("<img src=\"").append(exportName).append(".png\" name=\"myStatus\" width=\"100%\" border=\"0\">\n") //
                    .append("</body></html>\n").toString();
        // clang-format on

        //            ctx.contentType(MimeType.HTML.toString());

        RestServer.addLongPollingCookie(ctx, cookieNameExportLandingPage, now);
        ctx.result(builder.toString());
    });

    if (LOGGER.isInfoEnabled()) {
        LOGGER.atInfo().log("init rapidoidServer(..)");
    }
}

public class MyScreenShotData {
    private final String title;
    private final Image image;

    public MyScreenShotData(final String title, final Image image) {
        this.title = title;
        this.image = image;
    }

    public Image getImage() {
        return image;
    }

    public String getTitle() {
        return title;
    }
}
}
