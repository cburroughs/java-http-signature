/*
 * Copyright (c) 2015-2017, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.http.signature.google.httpclient;

import com.google.api.client.http.HttpRequest;
import com.joyent.http.signature.CryptoException;
import com.joyent.http.signature.Signer;
import com.joyent.http.signature.ThreadLocalSigner;
import org.bouncycastle.util.encoders.Base64;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.joyent.http.signature.Signer.AUTHZ_PATTERN;
import static com.joyent.http.signature.Signer.AUTHZ_SIGNING_STRING;

// I really really don't want to be using JUL for logging, but it is what the
// google library is using, so we are sticking with it. :(

/**
 * Class providing utility methods that allow you to sign a
 * {@link com.google.api.client.http.HttpRequest}.
 *
 * @see <a href="https://github.com/joyent/java-manta/blob/b2a180ff8a3ec3795ccc258904888f8305619756/src/main/java/com/joyent/manta/client/crypto/HttpSigner.java">Original Version</a>
 * @author Yunong Xiao
 * @author <a href="https://github.com/dekobon">Elijah Zupancic</a>
 * @since 1.0.0
 */
public class RequestHttpSigner {
    /**
     * The static logger instance.
     */
    private static final Logger LOG = Logger.getLogger(RequestHttpSigner.class.getName());

    /**
     * Public/private RSA keypair object used to sign HTTP requests.
     */
    private final KeyPair keyPair;

    /**
     * Login name/account name used in authorization header.
     */
    private final String login;

    /**
     * The RSA key fingerprint.
     */
    private final String fingerprint;

    /**
     * HTTP signature generator instance.
     */
    private final ThreadLocalSigner signer;

    /**
     * Creates a new instance allowing for HTTP signing.
     * @param keyPair Public/private RSA keypair object used to sign HTTP requests.
     * @param login Login name/account name used in authorization header
     * @param fingerprint rsa key fingerprint
     * @param useNativeCodeToSign true to enable native code acceleration of cryptographic singing
     */
    public RequestHttpSigner(final KeyPair keyPair, final String login, final String fingerprint,
                             final boolean useNativeCodeToSign) {
        this(keyPair, login, fingerprint, new ThreadLocalSigner(useNativeCodeToSign));
    }

    /**
     * Creates a new instance allowing for HTTP signing.
     * @param keyPair Public/private RSA keypair object used to sign HTTP requests.
     * @param login Login name/account name used in authorization header
     * @param fingerprint rsa key fingerprint
     * @param signer reference to thread-local signer
     */
    public RequestHttpSigner(final KeyPair keyPair, final String login, final String fingerprint,
                             final ThreadLocalSigner signer) {
        if (keyPair == null) {
            throw new IllegalArgumentException("KeyPair must be present");
        }

        if (login == null) {
            throw new IllegalArgumentException("Login must be present");
        }

        if (fingerprint == null) {
            throw new IllegalArgumentException("Fingerprint must be present");
        }

        this.keyPair = keyPair;
        this.login = login;
        this.fingerprint = fingerprint;
        this.signer = signer;
    }

    /**
     * Sign an {@link com.google.api.client.http.HttpRequest}.
     *
     * @param request The {@link com.google.api.client.http.HttpRequest} to sign.
     * @throws CryptoException If unable to sign the request.
     */
    public void signRequest(final HttpRequest request) {
        if (LOG.getLevel() != null && LOG.getLevel().equals(Level.FINER)) {
            LOG.finer(String.format("Signing request: %s", request.getHeaders()));
        }

        final String date;
        final String headerDate = request.getHeaders().getDate();
        if (headerDate != null && !headerDate.isEmpty()) {
            date = request.getHeaders().getDate();
        } else {
            date = signer.get().defaultSignDateAsString();
            request.getHeaders().setDate(date);
        }

        final String authzHeader = signer.get().createAuthorizationHeader(
                login, fingerprint, keyPair, date);
        request.getHeaders().setAuthorization(authzHeader);
    }


    /**
     * Signs an arbitrary URL using the Manta-compatible HTTP signature
     * method.
     *
     * Deprecated: Use method provided inside the Java Manta SDK.
     *
     * @param uri URI with no query pointing to a downloadable resource
     * @param method HTTP request method to be used in the signature
     * @param expires epoch time in seconds when the resource will no longer
     *                be available
     * @return a signed version of the input URI
     * @throws IOException thrown when we can't sign or read char data
     */
    @Deprecated
    public URI signURI(final URI uri, final String method, final long expires)
            throws IOException {
        Objects.requireNonNull(method, "Method must be present");
        Objects.requireNonNull(uri, "URI must be present");

        if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
            throw new IllegalArgumentException("Query must be empty");
        }

        final String charset = "UTF-8";
        final String algorithm = "RSA-SHA256";
        final String keyId = String.format("/%s/keys/%s",
                getLogin(), getFingerprint());
        final String keyIdEncoded = URLEncoder.encode(keyId, charset);

        StringBuilder sigText = new StringBuilder();
        sigText.append(method).append("\n")
                .append(uri.getHost()).append("\n")
                .append(uri.getPath()).append("\n")
                .append("algorithm=").append(algorithm).append("&")
                .append("expires=").append(expires).append("&")
                .append("keyId=").append(keyIdEncoded);


        StringBuilder request = new StringBuilder();
        final byte[] sigBytes = sigText.toString().getBytes();
        final byte[] signed = signer.get().sign(getLogin(), getFingerprint(), getKeyPair(), sigBytes);
        final String encoded = new String(Base64.encode(signed), charset);
        final String urlEncoded = URLEncoder.encode(encoded, charset);

        request.append(uri).append("?")
                .append("algorithm=").append(algorithm).append("&")
                .append("expires=").append(expires).append("&")
                .append("keyId=").append(keyIdEncoded).append("&")
                .append("signature=").append(urlEncoded);

        return URI.create(request.toString());
    }


    /**
     * Verifies the signature on a Google HTTP Client request.
     *
     * @param request request object to verify signature from
     * @return true if signature was verified correctly, otherwise false
     */
    public boolean verifyRequest(final HttpRequest request) {
        if (LOG.getLevel() != null && LOG.getLevel().equals(Level.FINER)) {
            LOG.finer(String.format("Verifying request: %s", request.getHeaders()));
        }
        String date = request.getHeaders().getDate();
        if (date == null) {
            throw new CryptoException("No date header in request");
        }

        date = String.format(AUTHZ_SIGNING_STRING, date);
        Signature signature = signer.get().getSignature();

        try {
            signature.initVerify(this.keyPair.getPublic());
            final String authzHeader = request.getHeaders().getAuthorization();
            final int startIndex = authzHeader.indexOf(AUTHZ_PATTERN);
            if (startIndex == -1) {
                throw new CryptoException("invalid authorization header " + authzHeader);
            }
            final String encodedSignedDate = authzHeader.substring(startIndex + AUTHZ_PATTERN.length(),
                    authzHeader.length() - 1);
            final byte[] signedDate = Base64.decode(encodedSignedDate.getBytes("UTF-8"));
            signature.update(date.getBytes("UTF-8"));
            return signature.verify(signedDate);
        } catch (final InvalidKeyException e) {
            throw new CryptoException("invalid key", e);
        } catch (final SignatureException e) {
            throw new CryptoException("invalid signature", e);
        } catch (final UnsupportedEncodingException e) {
            throw new CryptoException("invalid encoding", e);
        }
    }


    /**
     * @return Public/private RSA keypair object used to sign HTTP requests.
     */
    public KeyPair getKeyPair() {
        return keyPair;
    }


    /**
     * @return Login name/account name used in authorization header.
     */
    public String getLogin() {
        return login;
    }


    /**
     * @return The RSA key fingerprint.
     */
    public String getFingerprint() {
        return fingerprint;
    }

    /**
     * @return reference to thread-local {@link Signer}
     */
    public ThreadLocalSigner getSignerThreadLocal() {
        return signer;
    }
}
