/*
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.rapha.spring.reactive.security.auth.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.function.Predicate;

/**
 * Decides when a JWT string is valid.
 * First  try to parse it, then check that
 * the signature is correct.
 * If something fails an empty Mono is returning
 * meaning that is not valid.
 * Verify that expiration date is valid
 */
public class JWTCustomVerifier {

    private static final Logger log = LoggerFactory.getLogger(JWTCustomVerifier.class);

    private JWSVerifier jwsVerifier;

    public JWTCustomVerifier() {
        this.jwsVerifier = this.buildJWSVerifier();
    }

    public Mono<SignedJWT> check(String token) {
        return Mono.justOrEmpty(createJWS(token))
                .filter(isNotExpired)
                .filter(isFromTrustedIssuer)
                .filter(validSignature);
    }

    private final Predicate<SignedJWT> isNotExpired = token -> {
        Date expiration = getExpirationDate(token);
        return expiration != null && expiration.after(Date.from(Instant.now()));
    };

    private final Predicate<SignedJWT> isFromTrustedIssuer = token ->
            JWTTokenService.ISSUER.equals(getIssuer(token));

    private final Predicate<SignedJWT> validSignature = token -> {
        try {
            return token.verify(this.jwsVerifier);
        } catch (JOSEException e) {
            log.warn("Could not verify JWT signature", e);
            return false;
        }
    };

    private MACVerifier buildJWSVerifier() {
        try {
            return new MACVerifier(JWTSecrets.DEFAULT_SECRET);
        } catch (JOSEException e) {
            log.error("Could not build JWT verifier", e);
            return null;
        }
    }

    private SignedJWT createJWS(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            log.warn("Could not parse JWT", e);
            return null;
        }
    }

    private Date getExpirationDate(SignedJWT token) {
        try {
            return token.getJWTClaimsSet()
                    .getExpirationTime();
        } catch (ParseException e) {
            log.warn("Could not read expiration from JWT", e);
            return null;
        }
    }

    private String getIssuer(SignedJWT token) {
        try {
            return token.getJWTClaimsSet()
                    .getIssuer();
        } catch (ParseException e) {
            log.warn("Could not read issuer from JWT", e);
            return null;
        }
    }
}
