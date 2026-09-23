package org.matsim.project.progress;

import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.StartupListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Logs progress and an estimated finish time at the end of every iteration and
 * mirrors it to {@code progress.txt} in the output directory, so a running
 * simulation can be checked with a single {@code cat}.
 *
 * <p>The estimate uses the mean duration of the iterations run so far. With
 * LLM replanning the first iteration (no replanning) is faster than the rest,
 * so it is excluded from the mean once a later iteration exists.
 */
public final class EtaReporter implements StartupListener, IterationEndsListener {

    private static final Logger log = LogManager.getLogger(EtaReporter.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final Config config;
    private final OutputDirectoryHierarchy output;
    private LocalDateTime start;
    private LocalDateTime lastIterationEnd;
    private double replanningIterationSeconds = 0;
    private int replanningIterations = 0;

    @Inject
    EtaReporter(Config config, OutputDirectoryHierarchy output) {
        this.config = config;
        this.output = output;
    }

    @Override
    public void notifyStartup(StartupEvent event) {
        start = LocalDateTime.now();
        lastIterationEnd = start;
        write(header());
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        LocalDateTime now = LocalDateTime.now();
        double thisIteration = Duration.between(lastIterationEnd, now).toMillis() / 1000.0;
        lastIterationEnd = now;
        int first = config.controller().getFirstIteration();
        int last = config.controller().getLastIteration();
        int remaining = last - event.getIteration();
        if (event.getIteration() > first) {
            replanningIterationSeconds += thisIteration;
            replanningIterations++;
        }
        double perIteration = replanningIterations > 0
                ? replanningIterationSeconds / replanningIterations
                : thisIteration;
        double etaSeconds = remaining * perIteration;
        double elapsed = Duration.between(start, now).toMillis() / 1000.0;
        String line = String.format(Locale.ROOT,
                "iteration %d/%d done | this %s | mean %s/iteration | elapsed %s | remaining ~%s | ETA %s",
                event.getIteration(), last, hms(thisIteration), hms(perIteration), hms(elapsed), hms(etaSeconds),
                now.plusSeconds((long) etaSeconds).format(CLOCK));
        log.info("ETA: {}", line);
        System.out.println("[progress] " + line);
        write(header() + line + "\n" + (remaining == 0 ? "finished " + now.format(CLOCK) + "\n" : ""));
    }

    private String header() {
        int total = config.controller().getLastIteration() - config.controller().getFirstIteration() + 1;
        return "started " + start.format(CLOCK) + ", " + total + " iterations planned\n";
    }

    private static String hms(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%dh%02dm", s / 3600, (s % 3600) / 60)
                : String.format(Locale.ROOT, "%dm%02ds", s / 60, s % 60);
    }

    private void write(String text) {
        try {
            Files.writeString(Path.of(output.getOutputFilename("progress.txt")), text);
        } catch (IOException e) {
            log.warn("could not write progress.txt: {}", e.getMessage());
        }
    }

    /** Registers the reporter; add with {@code controler.addOverridingModule(new EtaReporter.Module())}. */
    public static final class Module extends AbstractModule {
        @Override
        public void install() {
            addControlerListenerBinding().to(EtaReporter.class).asEagerSingleton();
        }
    }
}
