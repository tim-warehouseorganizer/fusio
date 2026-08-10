package dev.flamelens.fusio.spring;

import dev.flamelens.fusio.Chars;
import dev.flamelens.fusio.Sink;
import dev.flamelens.fusio.interop.FusioCsvReader;
import dev.flamelens.fusio.pipes.Csv;
import dev.flamelens.fusio.pipes.Utf8;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code text/csv} message converter backed by fusio's fused RFC 4180
 * pipelines. Lets controllers speak CSV directly:
 *
 * <pre>{@code
 * @PostMapping(value = "/import", consumes = "text/csv")
 * ImportResult importRows(@RequestBody List<String[]> rows) { ... }
 *
 * @GetMapping(value = "/export", produces = "text/csv")
 * List<String[]> export() { ... }
 * }</pre>
 *
 * Reads: bytes -> BOM strip -> UTF-8 decode -> CSV parse, fused.
 * Writes: rows -> RFC 4180 format -> UTF-8 encode, fused, quoting only when
 * a field requires it. Supported binding types: {@code List<String[]>} and
 * {@code String[][]}.
 */
public class FusioCsvHttpMessageConverter implements GenericHttpMessageConverter<Object> {

    public static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return clazz == String[][].class && csvCompatible(mediaType);
    }

    @Override
    public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
        return supportedType(type) && csvCompatible(mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return (String[][].class == clazz || List.class.isAssignableFrom(clazz))
                && csvCompatible(mediaType);
    }

    @Override
    public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
        Type effective = type != null ? type : clazz;
        // writing is lenient about raw List types (RestClient passes the runtime
        // class for plain .body(list) calls); element types are checked in rowsOf
        boolean supported = supportedType(effective)
                || (effective instanceof Class<?> c
                        && (List.class.isAssignableFrom(c) || c == String[][].class));
        return supported && csvCompatible(mediaType);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return List.of(TEXT_CSV);
    }

    @Override
    public Object read(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
        return read((Type) clazz, clazz, inputMessage);
    }

    @Override
    public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (FusioCsvReader reader = FusioCsvReader.of(inputMessage.getBody())) {
            String[] row;
            while ((row = reader.readNext()) != null) {
                rows.add(row);
            }
        } catch (RuntimeException e) {
            throw new HttpMessageNotReadableException("Malformed CSV body: " + e.getMessage(),
                    e, inputMessage);
        }
        return type == String[][].class ? rows.toArray(new String[0][]) : rows;
    }

    @Override
    public void write(Object value, MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException {
        write(value, null, contentType, outputMessage);
    }

    @Override
    public void write(Object value, Type type, MediaType contentType,
                      HttpOutputMessage outputMessage) throws IOException {
        outputMessage.getHeaders().setContentType(TEXT_CSV);
        OutputStream body = outputMessage.getBody();
        Sink<String[]> sink = Csv.format().then(Utf8.encode()).connect(b -> {
            body.write(b.array, b.offset, b.length);
            return true;
        });
        for (String[] row : rowsOf(value)) {
            sink.accept(row);
        }
        sink.end();
        body.flush();
    }

    private static List<String[]> rowsOf(Object value) {
        if (value instanceof String[][] array) {
            return List.of(array);
        }
        if (value instanceof List<?> list) {
            List<String[]> rows = new ArrayList<>(list.size());
            for (Object element : list) {
                if (!(element instanceof String[] row)) {
                    throw new HttpMessageNotWritableException(
                            "text/csv writing supports List<String[]>; found element of type "
                                    + (element == null ? "null" : element.getClass().getName()));
                }
                rows.add(row);
            }
            return rows;
        }
        throw new HttpMessageNotWritableException(
                "text/csv writing supports List<String[]> or String[][]; got "
                        + value.getClass().getName());
    }

    private static boolean supportedType(Type type) {
        if (type == String[][].class) {
            return true;
        }
        if (type instanceof ParameterizedType p && p.getRawType() == List.class) {
            Type[] args = p.getActualTypeArguments();
            return args.length == 1 && args[0] == String[].class;
        }
        return false;
    }

    private static boolean csvCompatible(MediaType mediaType) {
        return mediaType == null || TEXT_CSV.isCompatibleWith(mediaType);
    }
}
