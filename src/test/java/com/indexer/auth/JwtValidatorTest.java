package com.indexer.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidatorTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "source-code-indexer";

    private static RSAKey rsaKey;
    private static ECKey ecKey;
    private static JWKSource<SecurityContext> testKeySource;

    @BeforeAll
    static void generateKeys() throws Exception {
        rsaKey = new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .generate();
        ecKey = new ECKeyGenerator(Curve.P_256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.ES256)
                .generate();
        testKeySource = new ImmutableJWKSet<>(new JWKSet(List.of(
                rsaKey.toPublicJWK(), ecKey.toPublicJWK())));
    }

    @Test
    void validTokenExtractsIdentityAndGroups() {
        String token = mintToken("alice", "Alice Chen",
                List.of("team-payments", "team-platform"), ISSUER, AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");
        CallerIdentity identity = validator.validate(token, "10.0.0.1");

        assertThat(identity.userId()).isEqualTo("alice");
        assertThat(identity.displayName()).isEqualTo("Alice Chen");
        assertThat(identity.authMethod()).isEqualTo("oauth");
        assertThat(identity.groups()).containsExactly("team-payments", "team-platform");
        assertThat(identity.sourceIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void missingGroupsClaimReturnsEmptyList() {
        String token = mintToken("bob", "Bob", null, ISSUER, AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");
        CallerIdentity identity = validator.validate(token, "10.0.0.1");

        assertThat(identity.groups()).isEmpty();
    }

    @Test
    void expiredTokenThrows() {
        String token = mintToken("alice", "Alice", List.of(), ISSUER, AUDIENCE, -120);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate(token, "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageMatching("(?i).*expired.*");
    }

    @Test
    void wrongIssuerThrows() {
        String token = mintToken("alice", "Alice", List.of(), "https://wrong-issuer.com", AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate(token, "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void wrongAudienceThrows() {
        String token = mintToken("alice", "Alice", List.of(), ISSUER, "wrong-audience", 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate(token, "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void malformedTokenThrows() {
        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");

        assertThatThrownBy(() -> validator.validate("not.a.jwt", "10.0.0.1"))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void customGroupsClaimName() {
        String token = mintTokenWithClaim("alice", "Alice", "roles",
                List.of("admin", "reader"), ISSUER, AUDIENCE, 300);

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "roles");
        CallerIdentity identity = validator.validate(token, "10.0.0.1");

        assertThat(identity.groups()).containsExactly("admin", "reader");
    }

    @Test
    void es256TokenIsAccepted() throws Exception {
        var builder = new JWTClaimsSet.Builder()
                .subject("carol")
                .claim("name", "Carol")
                .claim("groups", List.of("team-infra"))
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .issueTime(new Date());

        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(ecKey.getKeyID())
                        .build(),
                builder.build());
        jwt.sign(new ECDSASigner(ecKey));

        var validator = new JwtValidator(testKeySource, ISSUER, AUDIENCE, "groups");
        CallerIdentity identity = validator.validate(jwt.serialize(), "10.0.0.2");

        assertThat(identity.userId()).isEqualTo("carol");
        assertThat(identity.groups()).containsExactly("team-infra");
    }

    // --- Test helpers ---

    private static String mintToken(String sub, String name, List<String> groups,
                                    String issuer, String audience, int ttlSeconds) {
        return mintTokenWithClaim(sub, name, "groups", groups, issuer, audience, ttlSeconds);
    }

    private static String mintTokenWithClaim(String sub, String name, String groupsClaimName,
                                             List<String> groups, String issuer, String audience,
                                             int ttlSeconds) {
        try {
            var builder = new JWTClaimsSet.Builder()
                    .subject(sub)
                    .claim("name", name)
                    .issuer(issuer)
                    .audience(audience)
                    .expirationTime(new Date(System.currentTimeMillis() + ttlSeconds * 1000L))
                    .issueTime(new Date());

            if (groups != null) {
                builder.claim(groupsClaimName, groups);
            }

            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(rsaKey.getKeyID())
                            .build(),
                    builder.build());
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException("Failed to mint test token", e);
        }
    }
}
