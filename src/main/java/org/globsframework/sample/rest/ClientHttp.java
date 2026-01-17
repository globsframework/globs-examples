package org.globsframework.sample.rest;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.UniformReservoir;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.impl.DefaultConnectionReuseStrategy;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ClientHttp {

    public static void main(String[] args) throws Exception {

        HttpClient client = HttpClients.custom()
                .disableCookieManagement()
                .setConnectionReuseStrategy(DefaultConnectionReuseStrategy.INSTANCE)
                .build();

        final HttpHost httpHost = HttpHost.create("http://localhost:3100");
        for (int i = 0; i < 10000; i++) {
            final ClassicHttpRequest request = ClassicRequestBuilder.get()
                    .setHttpHost(httpHost)
                    .setPath("/api/greeting")
                    .setEntity("Some content".getBytes(StandardCharsets.UTF_8), ContentType.TEXT_PLAIN)
                    .build();
            callRemote(client, request);
        }
        final long startAt = System.currentTimeMillis() + 30000;

        Histogram histogram = new Histogram(new UniformReservoir());

        long count = 0;
        while (startAt > System.currentTimeMillis() ) {

            long start = System.nanoTime();
            final ClassicHttpRequest request = ClassicRequestBuilder.get()
                    .setHttpHost(httpHost)
                    .setPath("/api/greeting")
                    .setEntity(("Echo message # " + count).getBytes(StandardCharsets.UTF_8), ContentType.TEXT_PLAIN)
                    .build();

            callRemote(client, request);
            long end = System.nanoTime();
            count++;
            final long micros = TimeUnit.NANOSECONDS.toMicros(end - start);
            histogram.update(micros);
        }
        final Snapshot snapshot = histogram.getSnapshot();
        System.out.println("call per second " + count/30.);
        System.out.println("max : " + snapshot.getMax());
        System.out.println("min : " + snapshot.getMin());
        System.out.println("average : " + snapshot.getMean());
        System.out.println("99.9 : " + snapshot.get999thPercentile());
        System.out.println("99 : " + snapshot.get99thPercentile());
        System.out.println("98 : " + snapshot.get98thPercentile());
        System.out.println("95 : " + snapshot.get95thPercentile());


    }

    private static void callRemote(HttpClient client, ClassicHttpRequest request) throws IOException {
        client.execute(request,
                response -> {
                    final HttpEntity entity = response.getEntity();
                    long len = entity.getContentLength();
                    if (len > 0) {
                        entity.writeTo(OutputStream.nullOutputStream());
                    }
                    return null;
                });
    }
}
