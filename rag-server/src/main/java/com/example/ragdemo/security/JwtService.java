package com.example.ragdemo.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 轻量 JWT 生成与校验服务，使用 HS256 签名，避免在 Demo 中引入额外 JWT 运行时库。
 */
@Service
public class JwtService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long expireMinutes;

    public JwtService(
            @Value("${RAG_JWT_SECRET:please-change-this-rag-demo-jwt-secret}") String secret,
            @Value("${RAG_JWT_EXPIRE_MINUTES:720}") long expireMinutes
    ) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expireMinutes = expireMinutes;
    }

    public long expireSeconds() {
        return expireMinutes * 60;
    }

    public String createToken(AuthenticatedUser user) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", user.id().toString());
            payload.put("username", user.username());
            payload.put("role", user.role());
            payload.put("exp", Instant.now().plusSeconds(expireSeconds()).getEpochSecond());

            String headerPart = encodeJson(header);
            String payloadPart = encodeJson(payload);
            String signingInput = headerPart + "." + payloadPart;
            return signingInput + "." + sign(signingInput);
        } catch (Exception ex) {
            throw new IllegalStateException("生成登录凭证失败", ex);
        }
    }

    public Optional<AuthenticatedUser> parseToken(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String signingInput = parts[0] + "." + parts[1];
            if (!constantTimeEquals(parts[2], sign(signingInput))) {
                return Optional.empty();
            }

            Map<String, Object> payload = OBJECT_MAPPER.readValue(
                    BASE64_URL_DECODER.decode(parts[1]),
                    new TypeReference<>() {
                    }
            );
            long exp = ((Number) payload.getOrDefault("exp", 0)).longValue();
            if (exp < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }

            Long userId = Long.valueOf(String.valueOf(payload.get("sub")));
            String username = String.valueOf(payload.get("username"));
            String role = String.valueOf(payload.get("role"));
            return Optional.of(new AuthenticatedUser(userId, username, role));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String encodeJson(Map<String, Object> value) throws Exception {
        return BASE64_URL_ENCODER.encodeToString(OBJECT_MAPPER.writeValueAsBytes(value));
    }

    private String sign(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return BASE64_URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        if (leftBytes.length != rightBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < leftBytes.length; i++) {
            result |= leftBytes[i] ^ rightBytes[i];
        }
        return result == 0;
    }
}
