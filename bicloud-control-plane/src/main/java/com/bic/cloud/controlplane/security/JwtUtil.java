package com.bic.cloud.controlplane.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey signingKey;

    @PostConstruct
    void initSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes long");
        }
        signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username, String role, UUID userId) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("userId", userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(signingKey)
                .compact();
    }

    public String extractUsername(String token) {
        return getClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        Claims claims = getClaims(token);
        return claims.getSubject().equals(userDetails.getUsername())
                && userDetails instanceof BicloudUserDetails user
                && user.getId().toString().equals(claims.get("userId", String.class));
    }

    public long getExpiration() {
        return expiration;
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
