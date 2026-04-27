import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implement the two methods below. We expect this class to be stateless and thread safe.
 */
public class Census {
    /**
     * Number of cores in the current machine.
     */
    private static final int CORES = Runtime.getRuntime().availableProcessors();

    /**
     * Output format expected by our tests.
     */
    public static final String OUTPUT_FORMAT = "%d:%d=%d"; // Position:Age=Total

    /**
     * Factory for iterators.
     */
    private final Function<String, Census.AgeInputIterator> iteratorFactory;

    /**
     * Creates a new Census calculator.
     *
     * @param iteratorFactory factory for the iterators.
     */
    public Census(Function<String, Census.AgeInputIterator> iteratorFactory) {
        this.iteratorFactory = iteratorFactory;
    }

    /**
     * Given one region name, call {@link #iteratorFactory} to get an iterator for this region and return
     * the 3 most common ages in the format specified by {@link #OUTPUT_FORMAT}.
     */
    public String[] top3Ages(String region) {
        return format(countRegionSafely(region));
    }

    /**
     * Given a list of region names, call {@link #iteratorFactory} to get an iterator for each region and return
     * the 3 most common ages across all regions in the format specified by {@link #OUTPUT_FORMAT}.
     * We expect you to make use of all cores in the machine, specified by {@link #CORES).
     */
    public String[] top3Ages(List<String> regionNames) {
        if (regionNames == null || regionNames.isEmpty()) {
            return new String[]{};
        }

        int poolSize = Math.min(CORES, regionNames.size());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<Map<Integer, Long>>> futures = regionNames.stream()
                    .map(region -> pool.submit(() -> countRegionSafely(region)))
                    .collect(Collectors.toList());

            Map<Integer, Long> merged = new HashMap<>();
            for (Future<Map<Integer, Long>> future : futures) {
                try {
                    future.get().forEach((age, count) -> merged.merge(age, count, Long::sum));
                } catch (ExecutionException | InterruptedException ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return format(merged);
        } finally {
            pool.shutdown();
        }
    }

    private Map<Integer, Long> countRegionSafely(String region) {
        Map<Integer, Long> counts = new HashMap<>();
        AgeInputIterator iterator = null;
        try {
            iterator = iteratorFactory.apply(region);
            while (iterator.hasNext()) {
                Integer age = iterator.next();
                if (age == null || age < 0) {
                    continue;
                }
                counts.merge(age, 1L, Long::sum);
            }
        } catch (RuntimeException ex) {
            // Maybe just need to log here? Don't want to interupt the merge
        } finally {
            if (iterator != null) {
                try {
                    iterator.close();
                } catch (IOException ignored) {
                    // Best-effort close.
                }
            }
        }
        return counts;
    }

    private String[] format(Map<Integer, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return new String[]{};
        }

        List<Map.Entry<Integer, Long>> sorted = counts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .sorted(Comparator
                        .<Map.Entry<Integer, Long>, Long>comparing(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .collect(Collectors.toList());

        List<String> output = new ArrayList<>();
        int rank = 0;
        long previousCount = Long.MIN_VALUE;
        for (Map.Entry<Integer, Long> entry : sorted) {
            long count = entry.getValue();
            if (count != previousCount) {
                rank++;
                if (rank > 3) {
                    break;
                }
                previousCount = count;
            }
            output.add(String.format(OUTPUT_FORMAT, rank, entry.getKey(), count));
        }
        return output.toArray(new String[0]);
    }

    public interface AgeInputIterator extends Iterator<Integer>, Closeable {
    }
}
