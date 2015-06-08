/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.http;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.watcher.support.http.auth.ApplicableHttpAuth;
import org.elasticsearch.watcher.support.http.auth.HttpAuthRegistry;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * Client class to wrap http connections
 */
public class HttpClient extends AbstractLifecycleComponent<HttpClient> {

    static final String SETTINGS_SSL_PREFIX = "watcher.http.ssl.";
    static final String SETTINGS_SSL_SHIELD_PREFIX = "shield.ssl.";

    public static final String SETTINGS_SSL_PROTOCOL = SETTINGS_SSL_PREFIX + "protocol";
    static final String SETTINGS_SSL_SHIELD_PROTOCOL = SETTINGS_SSL_SHIELD_PREFIX + "protocol";
    public static final String SETTINGS_SSL_KEYSTORE = SETTINGS_SSL_PREFIX + "keystore.path";
    static final String SETTINGS_SSL_SHIELD_KEYSTORE = SETTINGS_SSL_SHIELD_PREFIX + "keystore.path";
    public static final String SETTINGS_SSL_KEYSTORE_PASSWORD = SETTINGS_SSL_PREFIX + "keystore.password";
    static final String SETTINGS_SSL_SHIELD_KEYSTORE_PASSWORD = SETTINGS_SSL_SHIELD_PREFIX + "keystore.password";
    public static final String SETTINGS_SSL_KEYSTORE_KEY_PASSWORD = SETTINGS_SSL_PREFIX + "keystore.key_password";
    static final String SETTINGS_SSL_SHIELD_KEYSTORE_KEY_PASSWORD = SETTINGS_SSL_SHIELD_PREFIX + "keystore.key_password";
    public static final String SETTINGS_SSL_KEYSTORE_ALGORITHM = SETTINGS_SSL_PREFIX + "keystore.algorithm";
    static final String SETTINGS_SSL_SHIELD_KEYSTORE_ALGORITHM = SETTINGS_SSL_SHIELD_PREFIX + "keystore.algorithm";
    public static final String SETTINGS_SSL_TRUSTSTORE = SETTINGS_SSL_PREFIX + "truststore.path";
    static final String SETTINGS_SSL_SHIELD_TRUSTSTORE = SETTINGS_SSL_SHIELD_PREFIX + "truststore.path";
    public static final String SETTINGS_SSL_TRUSTSTORE_PASSWORD = SETTINGS_SSL_PREFIX + "truststore.password";
    static final String SETTINGS_SSL_SHIELD_TRUSTSTORE_PASSWORD = SETTINGS_SSL_SHIELD_PREFIX + "truststore.password";
    public static final String SETTINGS_SSL_TRUSTSTORE_ALGORITHM = SETTINGS_SSL_PREFIX + "truststore.algorithm";
    static final String SETTINGS_SSL_SHIELD_TRUSTSTORE_ALGORITHM = SETTINGS_SSL_SHIELD_PREFIX + "truststore.algorithm";

    private final HttpAuthRegistry httpAuthRegistry;

    private SSLSocketFactory sslSocketFactory;

    @Inject
    public HttpClient(Settings settings, HttpAuthRegistry httpAuthRegistry) {
        super(settings);
        this.httpAuthRegistry = httpAuthRegistry;
    }

    @Override
    protected void doStart() throws ElasticsearchException {
        if (!settings.getByPrefix(SETTINGS_SSL_PREFIX).getAsMap().isEmpty() ||
                !settings.getByPrefix(SETTINGS_SSL_SHIELD_PREFIX).getAsMap().isEmpty()) {
            sslSocketFactory = createSSLSocketFactory(settings);
        } else {
            logger.trace("no ssl context configured");
            sslSocketFactory = null;
        }
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    public HttpResponse execute(HttpRequest request) throws IOException {
        try {
            return doExecute(request);
        } catch (SocketTimeoutException ste) {
            throw new ElasticsearchTimeoutException("failed to execute http request. timeout expired", ste);
        }
    }

    public HttpResponse doExecute(HttpRequest request) throws IOException {
        String queryString = null;
        if (request.params() != null && !request.params().isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, String> entry : request.params().entrySet()) {
                if (builder.length() != 0) {
                    builder.append('&');
                }
                builder.append(URLEncoder.encode(entry.getKey(), "utf-8"))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), "utf-8"));
            }
            queryString = builder.toString();
        }

        URI uri;
        try {
            uri = new URI(request.scheme().scheme(), null, request.host(), request.port(), request.path(), queryString, null);
        } catch (URISyntaxException e) {
            throw ExceptionsHelper.convertToElastic(e);
        }
        URL url = uri.toURL();

        logger.debug("making [{}] request to [{}]", request.method().method(), url);
        logger.trace("sending [{}] as body of request", request.body());
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        if (urlConnection instanceof HttpsURLConnection && sslSocketFactory != null) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) urlConnection;
            httpsConn.setSSLSocketFactory(sslSocketFactory);
        }

        urlConnection.setRequestMethod(request.method().method());
        if (request.headers() != null) {
            for (Map.Entry<String, String> entry : request.headers().entrySet()) {
                urlConnection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        if (request.auth() != null) {
            logger.trace("applying auth headers");
            ApplicableHttpAuth applicableAuth = httpAuthRegistry.createApplicable(request.auth);
            applicableAuth.apply(urlConnection);
        }
        urlConnection.setUseCaches(false);
        urlConnection.setRequestProperty("Accept-Charset", Charsets.UTF_8.name());
        if (request.body() != null) {
            urlConnection.setDoOutput(true);
            byte[] bytes = request.body().getBytes(Charsets.UTF_8.name());
            urlConnection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            urlConnection.getOutputStream().write(bytes);
            urlConnection.getOutputStream().close();
        }
        urlConnection.connect();

        final int statusCode = urlConnection.getResponseCode();
        ImmutableMap.Builder<String, String[]> responseHeaders = ImmutableMap.builder();
        for (Map.Entry<String, List<String>> header : urlConnection.getHeaderFields().entrySet()) {
            // HttpURLConnection#getHeaderFields returns the first status line as a header
            // with a `null` key (facepalm)... so we have to skip that one.
            if (header.getKey() != null) {
                responseHeaders.put(header.getKey(), header.getValue().toArray(new String[header.getValue().size()]));
            }
        }
        logger.debug("http status code [{}]", statusCode);
        if (statusCode < 400) {
            byte[] body = Streams.copyToByteArray(urlConnection.getInputStream());
            return new HttpResponse(statusCode, body, responseHeaders.build());
        }
        return new HttpResponse(statusCode, responseHeaders.build());
    }

    /** SSL Initialization **/
    private SSLSocketFactory createSSLSocketFactory(Settings settings) {
        try {
            String sslContextProtocol = settings.get(SETTINGS_SSL_PROTOCOL, settings.get(SETTINGS_SSL_SHIELD_PROTOCOL, "TLS"));
            String keyStore = settings.get(SETTINGS_SSL_KEYSTORE, settings.get(SETTINGS_SSL_SHIELD_KEYSTORE, System.getProperty("javax.net.ssl.keyStore")));
            String keyStorePassword = settings.get(SETTINGS_SSL_KEYSTORE_PASSWORD, settings.get(SETTINGS_SSL_SHIELD_KEYSTORE_PASSWORD, System.getProperty("javax.net.ssl.keyStorePassword")));
            String keyPassword = settings.get(SETTINGS_SSL_KEYSTORE_KEY_PASSWORD, settings.get(SETTINGS_SSL_SHIELD_KEYSTORE_KEY_PASSWORD, keyStorePassword));
            String keyStoreAlgorithm = settings.get(SETTINGS_SSL_KEYSTORE_ALGORITHM, settings.get(SETTINGS_SSL_SHIELD_KEYSTORE_ALGORITHM, System.getProperty("ssl.KeyManagerFactory.algorithm", KeyManagerFactory.getDefaultAlgorithm())));
            String trustStore = settings.get(SETTINGS_SSL_TRUSTSTORE, settings.get(SETTINGS_SSL_SHIELD_TRUSTSTORE, System.getProperty("javax.net.ssl.trustStore")));
            String trustStorePassword = settings.get(SETTINGS_SSL_TRUSTSTORE_PASSWORD, settings.get(SETTINGS_SSL_SHIELD_TRUSTSTORE_PASSWORD, System.getProperty("javax.net.ssl.trustStorePassword")));
            String trustStoreAlgorithm = settings.get(SETTINGS_SSL_TRUSTSTORE_ALGORITHM, settings.get(SETTINGS_SSL_SHIELD_TRUSTSTORE_ALGORITHM, System.getProperty("ssl.TrustManagerFactory.algorithm", TrustManagerFactory.getDefaultAlgorithm())));

            if (keyStore != null) {
                if (trustStore == null) {
                    logger.debug("keystore defined with no truststore defined, using keystore as truststore");
                    trustStore = keyStore;
                    trustStorePassword = keyStorePassword;
                    trustStoreAlgorithm = keyStoreAlgorithm;
                }
            } else if (trustStore == null) {
                logger.debug("no truststore defined, using system default");
            }

            if (trustStoreAlgorithm == null) {
                trustStoreAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
            }
            logger.debug("using protocol [{}], keyStore [{}], keyStoreAlgorithm [{}], trustStore [{}] and trustAlgorithm [{}]", sslContextProtocol, keyStore, keyStoreAlgorithm, trustStore, trustStoreAlgorithm);

            SSLContext sslContext = SSLContext.getInstance(sslContextProtocol);
            KeyManager[] keyManagers = keyManagers(keyStore, keyStorePassword, keyStoreAlgorithm, keyPassword);
            TrustManager[] trustManagers = trustManagers(trustStore, trustStorePassword, trustStoreAlgorithm);
            sslContext.init(keyManagers, trustManagers, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("http client failed to initialize the SSLContext", e);
        }
    }

    public SSLSocketFactory getSslSocketFactory() {
        return sslSocketFactory;
    }

    private static KeyManager[] keyManagers(String keyStore, String keyStorePassword, String keyStoreAlgorithm, String keyPassword) {
        if (keyStore == null) {
            return null;
        }
        Path path = Paths.get(keyStore);
        if (Files.notExists(path)) {
            return null;
        }

        try {
            // Load KeyStore
            KeyStore ks = readKeystore(path, keyStorePassword);

            // Initialize KeyManagerFactory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(keyStoreAlgorithm);
            kmf.init(ks, keyPassword.toCharArray());
            return kmf.getKeyManagers();
        } catch (Exception e) {
            throw new RuntimeException("http client failed to initialize a KeyManagerFactory", e);
        }
    }

    private static TrustManager[] trustManagers(String trustStore, String trustStorePassword, String trustStoreAlgorithm) {
        try {
            // Load TrustStore
            KeyStore ks = null;
            if (trustStore != null) {
                Path trustStorePath = Paths.get(trustStore);
                if (Files.exists(trustStorePath)) {
                    ks = readKeystore(trustStorePath, trustStorePassword);
                }
            }

            // Initialize a trust manager factory with the trusted store
            TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(trustStoreAlgorithm);
            trustFactory.init(ks);
            return trustFactory.getTrustManagers();
        } catch (Exception e) {
            throw new RuntimeException("http client failed to initialize a TrustManagerFactory", e);
        }
    }

    private static KeyStore readKeystore(Path path, String password) throws Exception {
        try (InputStream in = Files.newInputStream(path)) {
            // Load TrustStore
            KeyStore ks = KeyStore.getInstance("jks");
            assert password != null;
            ks.load(in, password.toCharArray());
            return ks;
        }
    }
}
