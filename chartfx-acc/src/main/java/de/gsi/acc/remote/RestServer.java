package de.gsi.acc.remote;

import static j2html.TagCreator.attrs;
import static j2html.TagCreator.body;
import static j2html.TagCreator.button;
import static j2html.TagCreator.form;
import static j2html.TagCreator.h1;
import static j2html.TagCreator.head;
import static j2html.TagCreator.html;
import static j2html.TagCreator.input;
import static j2html.TagCreator.link;
import static j2html.TagCreator.main;
import static j2html.TagCreator.title;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.servlet.ServletOutputStream;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;

import io.javalin.Javalin;
import io.javalin.core.compression.CompressionStrategy;
import io.javalin.core.compression.Gzip;
import io.javalin.core.security.Role;
import io.javalin.core.util.Header;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.sse.SseClient;
import io.javalin.http.sse.SseHandler;
import io.javalin.http.util.RateLimit;
import j2html.tags.ContainerTag;
import j2html.tags.Tag;
/**
 * Small RESTful server helper class. 
 * 
 * The primary purposes of this utility class is to provide 
 * a) some convenience methods, and 
 * b) to wrap the primary REST server implementation in view of easier maintenance, back-end server upgrades or changing API. 
 * 
 * @author rstein
 */
public final class RestServer { // NOPMD -- nomen est omen
    private static final Logger LOGGER = LoggerFactory.getLogger(RestServer.class);
    private static final String ENDPOINT_HELLO = "/hello";
    private static final String ENDPOINT_HELLO_NAME_PARAM = "/hello/:name";
    private static final int SERVER_JAVALIN = 8080;
    public static final String ENDPOINT_BYTE_BUFFER = "/byteBuffer";

    private static final int N_DATA = 100;
    private static final byte[] BYTE_BUFFER = new byte[N_DATA];

    private static Javalin instance;

    private static final ConcurrentMap<String, Queue<SseClient>> eventListener = new ConcurrentHashMap<>();
    private static final ObservableList<String> endpoints = FXCollections.observableArrayList();

    private RestServer() {
        // this is a utility class
    }

    public static ObservableList<String> getEndpoints() {
        return endpoints;
    }

    public static Queue<SseClient> getEventClients(final String endpointName) {
        if (endpointName == null || endpointName.isEmpty()) {
            throw new IllegalArgumentException(new StringBuilder().append("invalid endpointName '").append(endpointName).append("'").toString());
        }

        final String fullEndPointName = '/' == endpointName.charAt(0) ? endpointName : "/" + endpointName;
        final Queue<SseClient> ret = eventListener.get(fullEndPointName);
        if (ret == null) {
            throw new IllegalArgumentException(new StringBuilder().append("endpointName '").append(fullEndPointName).append("' not registered").toString());
        }
        return ret;
    }

    public static Javalin getInstance() {
        if (instance == null) {
            startRestServer("", SERVER_JAVALIN);
        }
        return instance;
    }

    public static void registerEndpoint(final String endpointName, Handler userHandler) {
        if (endpointName == null || endpointName.isEmpty()) {
            throw new IllegalArgumentException(new StringBuilder().append("invalid endpointName '").append(endpointName).append("'").toString());
        }
        if (userHandler == null) {
            throw new IllegalArgumentException(new StringBuilder().append("user-provided handler for  endpointName '").append(endpointName).append("' is null").toString());
        }

        final String fullEndPointName = '/' == endpointName.charAt(0) ? endpointName : "/" + endpointName;
        final HashSet<Role> permittedRoles = new HashSet<>();
        endpoints.add(fullEndPointName);
        eventListener.computeIfAbsent(fullEndPointName, key -> new ConcurrentLinkedQueue<>());

        final SseHandler sseHandler = new SseHandler(client -> {
            System.err.println(new StringBuilder().append("sse interface invoked for '").append(fullEndPointName).append("' and client: ").append(client.ctx.req.getRemoteHost()).toString());
            getEventClients(fullEndPointName).add(client);
            client.sendEvent("connected", "Hello, SSE " + client.ctx.req.getRemoteHost());

            client.onClose(() -> {
                System.err.println("removed client: " + client.ctx.req.getRemoteHost());
                getEventClients(fullEndPointName).remove(client);
            });
        });

        final Handler localHandler = ctx -> {
            if (MimeType.EVENT_STREAM.toString().equals(ctx.header(Header.ACCEPT))) {
                sseHandler.handle(ctx);
                return;
            }
            userHandler.handle(ctx);
        };
        instance.addHandler(HandlerType.GET, endpointName, localHandler, permittedRoles);
    }

    public static void startRestServer(final int hostPort) {
        startRestServer("", hostPort);
    }

    public static void startRestServer(final String hostName, final int hostPort) {
        instance = Javalin.create(config -> {
                              config.enableCorsForAllOrigins();
                              config.showJavalinBanner = false;
                              // config.defaultContentType = MimeType.BINARY.toString();
                              config.compressionStrategy(null, new Gzip(0));
                              config.inner.compressionStrategy = CompressionStrategy.NONE;
                              config.server(() -> RestServer.createHttp2Server(hostName, hostPort));
                          })
                           .start();
        //        if (hostName == null || hostName.isEmpty()) {
        //            instance.start(hostPort);
        //        } else {
        //            instance.start(hostName, hostPort);
        //        }

        // some default routes
        registerEndpoint("/", ctx -> ctx.result("available end points" + endpoints.stream().collect(Collectors.joining(", ", "[", "]"))));
        registerEndpoint(ENDPOINT_HELLO, ctx -> ctx.result("Hello World"));
        registerEndpoint(ENDPOINT_HELLO_NAME_PARAM, ctx -> ctx.result(new StringBuilder().append("Hello: ").append(ctx.pathParam("name")).append("!").toString()));

        initDefaultRoutes();
    }

    private static Server createHttp2Server(final String hostName, final int hostPort) {
        Server server = new Server();

        ServerConnector connector = new ServerConnector(server);
        if (hostName != null && !hostName.isEmpty()) {
            connector.setHost(hostName);
        }
        connector.setPort(hostPort);
        server.addConnector(connector);

        // HTTP Configuration
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(8443);

        // SSL Context Factory for HTTPS and HTTP/2
        SslContextFactory sslContextFactory = new SslContextFactory(); // trust all certificates
        System.err.println("keyStore raw = " + RestServer.class.getResource("/keystore.jks"));
        String keyStore = RestServer.class.getResource("/keystore.jks").toExternalForm();
        System.err.println("keyStore = " + keyStore);
        sslContextFactory.setKeyStorePath(keyStore); // replace with your real keystore
        sslContextFactory.setKeyStorePassword("nopassword"); // replace with your real password
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);
        sslContextFactory.setProvider("Conscrypt");

        // HTTPS Configuration
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());

        // HTTP/2 Connection Factory
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol("h2");

        // SSL Connection Factory
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());

        // HTTP/2 Connector
        ServerConnector http2Connector = new ServerConnector(server, ssl, alpn, h2, new HttpConnectionFactory(httpsConfig));
        http2Connector.setPort(8443);
        server.addConnector(http2Connector);

        return server;
    }

    public static void writeBytesToContext(@NotNull final Context ctx, final byte[] bytes, final int nSize) {
        // based on the suggestion at https://github.com/tipsy/javalin/issues/910
        try (ServletOutputStream outputStream = ctx.res.getOutputStream()) {
            outputStream.write(bytes, 0, nSize);
        } catch (IOException e) {
            LOGGER.atError().setCause(e);
        }
    }

    /**
     * Suppresses caching for this end point
     * @param ctx end point context handler
     */
    public static void suppressCaching(final Context ctx) {
        // for for HTTP 1.1
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control
        ctx.res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        // for HTTP 1.0
        ctx.res.addHeader("Pragma", "no-cache");

        // for proxies: TODO: need to check an appropriate value
        ctx.res.addHeader("Expires", "0");
    }

    public static void addLongPollingCookie(final Context ctx, final String key, final long lastUpdateMillies) {
        // N.B. this is a workaround since javax.servlet.http.Cookie does not support the SameSite cookie field.
        // workaround inspired by: https://github.com/tipsy/javalin/issues/780
        final String cookieComment = "stores the servcer-side time stamp of the last valid update (required for long-polling)";
        final String cookie = new StringBuilder().append(key).append("=").append(lastUpdateMillies) //
                                      .append("; Comment=\"")
                                      .append(cookieComment)
                                      .append("\"; Expires=-1; SameSite=Strict;")
                                      .toString();
        ctx.res.addHeader("Set-Cookie", cookie);

        //        byte[] ipAddress = new byte[] {(byte)192, (byte)168, (byte)1, (byte)1 };
        //        InetAddress address = InetAddress.getByAddress(ipAddress);
        //        String hostnameCanonical = address.getCanonicalHostName();
        //        System.out.println(hostnameCanonical);
        //        cookie.setDomain(hostnameCanonical);
        //        ctx.cookie("SameSite", "Strict");
        //        ctx.cookie(cookie);
    }

    /**
     * guards this end point and returns HTTP error response if predefined rate limit is exceeded
     * 
     * @param ctx end point context handler
     * @param numRequests number of callse
     * @param timeUnit time base reference
     */
    public static void applyRateLimit(final Context ctx, final int numRequests, final TimeUnit timeUnit) {
        new RateLimit(ctx).requestPerTimeUnit(numRequests, timeUnit); //
    }

    protected static void initDefaultRoutes() {
        registerEndpoint(ENDPOINT_BYTE_BUFFER, ctx -> {
            final String type = ctx.header(Header.ACCEPT);
            System.err.println("started bytebuffer endpoint - accept header = " + type);

            if (type == null || type.equalsIgnoreCase(MimeType.JSON.toString())) {
                // ctx.contentType(MimeType.JSON.toString()).result(JSON.toJSONString(new
                // MyBinaryData(BYTE_BUFFER)));
                // alt 1:
                final String returnString = JSON.toJSONString(BYTE_BUFFER);
                final byte[] bytes = returnString.getBytes(StandardCharsets.UTF_8);
                // alt 2:
                // final byte[] bytes = JSON.toJSONBytes(new MyBinaryData(BYTE_BUFFER));
                writeBytesToContext(ctx, bytes, bytes.length);
                return;
            } else if (type.equalsIgnoreCase(MimeType.BINARY.toString())) {
                writeBytesToContext(ctx, BYTE_BUFFER, BYTE_BUFFER.length);
                return;
            }
            // default return type for unspecified mime type
            writeBytesToContext(ctx, BYTE_BUFFER, BYTE_BUFFER.length);
        });

        registerEndpoint("test", ctx -> {
            // clang-format off
            ContainerTag ret = html(
                head(
                    title("Title"),
                    link().withRel("stylesheet").withHref("/css/main.css")
                ),
                body(
                    main(attrs("#main.content"),
                        h1("Heading!"),
                        h1("Please sign up"),
                        form().withMethod("post").with(
                            emailInput("Email address"),
                            choosePasswordInput("Choose Password"),
                            repeatPasswordInput("Repeat Password"),
                            submitButton("Sign up")
                        )
                    )
                )
            );
            // clang-format on
            ctx.res.setContentType(MimeType.HTML.toString());
            ctx.result(ret.render());
        });
    }

    public static Tag enterPasswordInput(String placeholder) {
        return passwordInput("enterPassword", placeholder);
    }

    public static Tag choosePasswordInput(String placeholder) {
        return passwordInput("choosePassword", placeholder);
    }

    public static Tag repeatPasswordInput(String placeholder) {
        return passwordInput("repeatPassword", placeholder);
    }

    public static Tag passwordInput(String identifier, String placeholder) {
        return input()
                .withType("password")
                .withId(identifier)
                .withName(identifier)
                .withPlaceholder(placeholder)
                .isRequired();
    }

    public static Tag emailInput(String placeholder) {
        return input()
                .withType("email")
                .withId("email")
                .withName("email")
                .withPlaceholder(placeholder)
                .isRequired();
    }

    public static Tag submitButton(String text) {
        return button(text).withType("submit");
    }
}
