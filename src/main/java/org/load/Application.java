package org.load;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class Application {

    public static void main(String[] args) throws InterruptedException {
        int threadCount = 1;
        int loadLoopsCount = 10;
        System.out.println("Using " + threadCount + " threads.");

        Task task = new Task();

        System.out.println("Warm up loop");
        new LoadTest(threadCount, 1_000, task).start();

        for (int i = 1; i < loadLoopsCount + 1; i++) {
            System.out.println("\n" + i + " loop");
            new LoadTest(threadCount, 1_000, task).start();
        }
    }

    static class Task implements Runnable {

        private final HttpClient httpClient;

        public Task() {
            this.httpClient = HttpClient.newBuilder()
                                        .version(HttpClient.Version.HTTP_2)
                                        .build();
        }

        @Override
        public void run() {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                                                 .GET()
                                                 .uri(URI.create("https://nginx.website/Sample-text-file-500kb.txt"))
//                                                 .uri(URI.create("https://nginx.website/100-files.zip"))
                                                 .header("accept-encoding", "gzip")
                                                 .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                response.headers().firstValue("content-encoding")
                        .filter(v->v.equalsIgnoreCase("gzip"))
                        .orElseThrow(()->new IllegalStateException("Content-encoding not gzip"));

                if (response.version() != HttpClient.Version.HTTP_2) {
                    throw new IllegalStateException("The client didn't upgraded to HTTP 2");
                }

                if (response.statusCode() != 200) {
                    System.err.println("Got " + response.statusCode() + " in response.");
                }

                if (response.body() == null) {
                    System.err.println("Got empty body in response.");
                }
            } catch (IOException | InterruptedException e) {
                if (!e.getMessage().contains("GOAWAY")) {
                    System.err.println("Got exception:\n" + e.getMessage());
                }
            }
        }
    }

    static class LoadTest {

        AtomicLong counter = new AtomicLong();

        ExecutorService executor;
        long taskLoopCount;
        Runnable runnable;

        Instant startTime;

        public LoadTest(int threadCount, long taskLoopCount, Runnable runnable) {
            this.executor = Executors.newFixedThreadPool(threadCount);
            this.taskLoopCount = taskLoopCount;
            this.runnable = runnable;
        }

        public void start() throws InterruptedException {
            startTime = Instant.now();

            for (int i = 0; i < taskLoopCount; i++) {
                executor.execute(() -> {
                    runnable.run();
                    counter.updateAndGet(operand -> {
                        if (++operand % (taskLoopCount / 10) == 0) {
                            System.out.println((operand * 100) / taskLoopCount + "% requests executed.");
                        }
                        return operand;
                    });
                });
            }
            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, MILLISECONDS);

            printResults();
        }


        void printResults() {
            Duration duration = Duration.between(startTime, Instant.now());
            System.out.println("Executed " + counter.get() + " requests " +
                                       "in " + duration.toMillis() + " millis " +
                                       "with " + duration.toMillis() / counter.get() + " millis per request in average.");
        }
    }
}
