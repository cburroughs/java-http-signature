/*
 * Copyright (c) 2015-2017, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.http.signature.jaxrs.client;

import com.joyent.http.signature.Signer;
import com.joyent.http.signature.ThreadLocalSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Objects;


/**
 * A Jersey ClientFilter for signing HTTP requests for Joyent Cloud API and Manta service.
 *
 * @author <a href="https://github.com/phillipross">Phillip Ross</a>
 */
public class SignedRequestClientRequestFilter implements ClientRequestFilter {

    /**
     * The static logger instance.
     */
    private static final Logger logger = LoggerFactory.getLogger(SignedRequestClientRequestFilter.class);

    /**
     * Name of the authorization HTTP header.
     */
    public static final String AUTHORIZATION_HEADER_NAME = "Authorization";

    /**
     * Name of the date HTTP header.
     */
    public static final String DATE_HEADER_NAME = "Date";

    /**
     * Login name associated with the Joyent Cloud or Manta service.
     */
    private String loginName;

    /**
     * RSA key fingerprint of the key used to access the Joyent Cloud or Manta service.
     */
    private String keyId;

    /**
     * Path on the filesystem to the private RSA key used to access the Joyent Cloud or Manta service.
     */
    private String keyPath;

    /**
     * Cryptographic signer instance.
     */
    private ThreadLocal<Signer> signer;

    /**
     * Creates a new filter instance with the specified credentials for signing requests.
     *
     * @param loginName Login name associated with the Joyent Cloud or Manta service
     * @param keyId RSA key fingerprint of the key used to access the Joyent Cloud or Manta service
     * @param keyPath Path on the filesystem to the private RSA key used to access the Joyent Cloud or Manta service
     */
    public SignedRequestClientRequestFilter(final String loginName, final String keyId, final String keyPath) {
        Objects.requireNonNull(loginName, "loginName must be specified");
        Objects.requireNonNull(keyId, "keyId must be specified");
        Objects.requireNonNull(keyPath, "keyPath must be specified");
        this.loginName = loginName;
        this.keyId = keyId;
        this.keyPath = keyPath;
        this.signer = new ThreadLocalSigner();
    }


    /**
     * Adds date and authorization headers to the request.
     *
     * @param requestContext request context.
     * @throws IOException if an I/O exception occurs.
     */
    @Override
    public void filter(final ClientRequestContext requestContext) throws IOException {
        final MultivaluedMap<String, Object> headers = requestContext.getHeaders();
        final Date now = new Date();
        final String dateHeaderValue = Signer.DATE_FORMAT.format(now);
        headers.add(DATE_HEADER_NAME, dateHeaderValue);
        final String authHeaderValue = signer.get().createAuthorizationHeader(
                loginName,
                keyId,
                signer.get().getKeyPair(Paths.get(keyPath)),
                now
        );
        headers.add(AUTHORIZATION_HEADER_NAME, authHeaderValue);
    }
}
