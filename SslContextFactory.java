package com.nonstop.proxy.util;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * Crea SSLContext restringido a TLS 1.2 y TLS 1.3.
 *
 * Uso típico:
 *   SSLContext ctx    = SslContextFactory.createDefault();
 *   SSLParameters params = SslContextFactory.tlsParameters();
 *
 * Para API Gateways corporativos con CA privada:
 *   SSLContext ctx = SslContextFactory.createWithTrustStore(path, password);
 *
 * Variables de entorno opcionales:
 *   PROXY_TRUSTSTORE_PATH      Ruta al archivo JKS con la CA corporativa
 *   PROXY_TRUSTSTORE_PASSWORD  Contraseña del truststore
 *   PROXY_KEYSTORE_PATH        Ruta al JKS con el certificado de cliente (mTLS)
 *   PROXY_KEYSTORE_PASSWORD    Contraseña del keystore de cliente
 */
public final class SslContextFactory {

    private static final String[] ALLOWED_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};

    private SslContextFactory() {}

    /**
     * Crea un SSLContext usando el truststore por defecto de la JVM.
     * Suficiente para APIs con certificados firmados por una CA pública conocida.
     */
    public static SSLContext createDefault() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo inicializar SSLContext por defecto", e);
        }
    }

    /**
     * Crea SSLContext con truststore JKS personalizado (CA corporativa privada).
     * Parámetros leídos desde variables de entorno si truststorePath es null.
     */
    public static SSLContext createWithTrustStore(String truststorePath, String truststorePassword) {
        String path = truststorePath != null
            ? truststorePath
            : System.getenv().getOrDefault("PROXY_TRUSTSTORE_PATH", "");
        String pass = truststorePassword != null
            ? truststorePassword
            : System.getenv().getOrDefault("PROXY_TRUSTSTORE_PASSWORD", "");

        if (path.isEmpty()) {
            return createDefault();
        }

        try {
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(path)) {
                trustStore.load(fis, pass.toCharArray());
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;

        } catch (Exception e) {
            throw new IllegalStateException(
                "Error al cargar truststore desde: " + path, e);
        }
    }

    /**
     * Crea SSLContext con mTLS (certificado de cliente + truststore corporativo).
     * Requerido cuando el API Gateway exige autenticación mutua TLS.
     */
    public static SSLContext createWithMutualTls(String keystorePath, String keystorePassword,
                                                  String truststorePath, String truststorePassword) {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(keystorePath)) {
                keyStore.load(fis, keystorePassword.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());

            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(truststorePath)) {
                trustStore.load(fis, truststorePassword.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ctx;

        } catch (Exception e) {
            throw new IllegalStateException("Error al configurar mTLS", e);
        }
    }

    /**
     * SSLParameters que restringe los protocolos aceptados a TLS 1.2 y TLS 1.3.
     * Debe pasarse al HttpClient.Builder.sslParameters().
     */
    public static SSLParameters tlsParameters() {
        SSLParameters params = new SSLParameters();
        params.setProtocols(ALLOWED_PROTOCOLS);
        return params;
    }

    /**
     * Crea SSLContext según las variables de entorno disponibles.
     * Llamado desde ApiGatewayClient en su constructor.
     */
    public static SSLContext createFromEnv() {
        String tsPath  = System.getenv("PROXY_TRUSTSTORE_PATH");
        String ksPath  = System.getenv("PROXY_KEYSTORE_PATH");

        if (ksPath != null && !ksPath.isEmpty()) {
            return createWithMutualTls(
                ksPath,  System.getenv().getOrDefault("PROXY_KEYSTORE_PASSWORD", ""),
                tsPath != null ? tsPath : "",
                System.getenv().getOrDefault("PROXY_TRUSTSTORE_PASSWORD", ""));
        }
        if (tsPath != null && !tsPath.isEmpty()) {
            return createWithTrustStore(tsPath,
                System.getenv().getOrDefault("PROXY_TRUSTSTORE_PASSWORD", ""));
        }
        return createDefault();
    }
}
