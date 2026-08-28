package dev.flamelens.fusio.spring;

import dev.flamelens.fusio.ByteSource;
import dev.flamelens.fusio.pipes.Gzip;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.ByteArrayOutputStream;

/**
 * Decorates any {@link RedisSerializer}, transparently gzipping values whose
 * serialized form is at or above a size threshold. The win is Redis memory
 * and network, not CPU: large cached blobs (reports, aggregations) typically
 * shrink 5-10x. Values below the threshold are stored untouched.
 *
 * <p>Compressed values are recognized on read by the gzip magic bytes
 * ({@code 1f 8b 08}), so pre-existing uncompressed entries keep deserializing
 * — enabling this on a warm cache is safe. Do NOT use it if your delegate's
 * raw output can itself begin with the gzip magic (JSON and JDK serialization
 * cannot); and note that after values are written compressed, removing the
 * wrapper requires a cache flush.
 *
 * <p>Wire it where you build your cache configurations, e.g.:
 * <pre>{@code
 * var json = new Jackson3JsonRedisSerializer<>(mapper, ReportDto.class);
 * var value = new GzipWrappingRedisSerializer<>(json, 8 * 1024);
 * RedisCacheConfiguration.defaultCacheConfig()
 *         .serializeValuesWith(SerializationPair.fromSerializer(value));
 * }</pre>
 */
public class GzipWrappingRedisSerializer<T> implements RedisSerializer<T> {

    private final RedisSerializer<T> delegate;
    private final int compressAbove;

    public GzipWrappingRedisSerializer(RedisSerializer<T> delegate, int compressAbove) {
        if (compressAbove < 64) {
            throw new IllegalArgumentException("compressAbove must be >= 64 bytes: " + compressAbove);
        }
        this.delegate = delegate;
        this.compressAbove = compressAbove;
    }

    @Override
    public byte[] serialize(T value) throws SerializationException {
        byte[] raw = delegate.serialize(value);
        if (raw == null || raw.length < compressAbove) {
            return raw;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length / 4 + 64);
        ByteSource.of(raw).via(Gzip.gzip()).forEach(b -> bos.write(b.array, b.offset, b.length));
        return bos.toByteArray();
    }

    @Override
    public T deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || !isGzip(bytes)) {
            return delegate.deserialize(bytes);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(bytes.length * 4);
        ByteSource.of(bytes).via(Gzip.gunzip()).forEach(b -> bos.write(b.array, b.offset, b.length));
        return delegate.deserialize(bos.toByteArray());
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0x1f
                && bytes[1] == (byte) 0x8b
                && bytes[2] == 8;
    }
}
