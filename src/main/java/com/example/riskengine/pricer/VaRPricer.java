package com.example.riskengine.pricer;

import com.example.riskengine.model.EquityTrade;
import com.example.riskengine.model.Trade;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.decomposition.chol.CholeskyDecompositionCommon_DDRM;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.interfaces.decomposition.CholeskyDecomposition_F64;

import java.util.*;

/**
 * Monte Carlo Value-at-Risk pricer using a delta-normal approximation.
 *
 * <h2>Method</h2>
 * <ol>
 *   <li>Build the unique underlier universe from the trade list.</li>
 *   <li>Accept (or auto-generate) a covariance matrix Σ for daily log-returns.</li>
 *   <li>Cholesky-decompose Σ = L·Lᵀ via EJML {@link DMatrixRMaj}.</li>
 *   <li>Draw {@code numScenarios} correlated standard-normal shock vectors
 *       z ~ N(0, Σ) by computing L·ε where ε ~ N(0,I).</li>
 *   <li>For each scenario, shift every underlier spot by its simulated return
 *       and re-price each trade using the Black-Scholes delta (linear P&L
 *       approximation: ΔP ≈ delta × quantity × ΔS).</li>
 *   <li>Sort the portfolio P&L distribution and read off the requested
 *       percentile loss quantiles (95 %, 99 %, 99.9 %).</li>
 * </ol>
 *
 * <h2>Confidence levels</h2>
 * <pre>
 *   PCTILE_95   → 1-day 95 % VaR
 *   PCTILE_99   → 1-day 99 % VaR
 *   PCTILE_999  → 1-day 99.9 % VaR  (tail / stressed VaR proxy)
 * </pre>
 *
 * <h2>Standalone usage</h2>
 * <pre>{@code
 *   VaRPricer var = new VaRPricer(10_000);
 *   VaRResult result = var.calculate(trades);
 *   System.out.println(result);
 * }</pre>
 *
 * The covariance matrix defaults to 20 % annual vol (≈ 1.26 % daily) with
 * 30 % pairwise correlation between every pair of underliers.  Pass a custom
 * {@code double[][] covMatrix} (indexed in underlier-sorted order) to
 * override.
 */
public class VaRPricer {

    // ------------------------------------------------------------------ //
    //  Confidence-level constants
    // ------------------------------------------------------------------ //

    public static final double PCTILE_95  = 0.95;
    public static final double PCTILE_99  = 0.99;
    public static final double PCTILE_999 = 0.999;

    // ------------------------------------------------------------------ //
    //  Default market parameters
    // ------------------------------------------------------------------ //

    /** Annual vol used when no covariance matrix is supplied. */
    private static final double DEFAULT_ANNUAL_VOL   = 0.20;

    /** Daily vol = annual vol / √252. */
    private static final double DEFAULT_DAILY_VOL    = DEFAULT_ANNUAL_VOL / Math.sqrt(252.0);

    /** Pairwise correlation applied between every pair of underliers. */
    private static final double DEFAULT_CORRELATION  = 0.30;

    // ------------------------------------------------------------------ //
    //  Fields
    // ------------------------------------------------------------------ //

    private final int numScenarios;
    private final Random rng;

    /** Optional user-supplied covariance matrix (underlier-sorted order). */
    private double[][] customCovMatrix;

    // ------------------------------------------------------------------ //
    //  Constructors
    // ------------------------------------------------------------------ //

    /**
     * @param numScenarios number of Monte Carlo draws (e.g. 10 000).
     *                     Higher values give smoother tail estimates but cost
     *                     proportionally more CPU.
     */
    public VaRPricer(int numScenarios) {
        this(numScenarios, new Random(42L));
    }

    /**
     * @param numScenarios number of Monte Carlo draws.
     * @param rng          seeded {@link Random} for reproducible results.
     */
    public VaRPricer(int numScenarios, Random rng) {
        if (numScenarios < 100) throw new IllegalArgumentException(
                "numScenarios must be >= 100 for stable quantile estimates");
        this.numScenarios = numScenarios;
        this.rng = rng;
    }

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * Supply a custom daily-return covariance matrix.
     * Rows/columns must be ordered by the lexicographic sort of underlier
     * names (same ordering used internally by {@link #calculate}).
     *
     * @param cov symmetric positive-definite matrix of size n×n
     *            where n is the number of distinct underliers.
     */
    public VaRPricer withCovarianceMatrix(double[][] cov) {
        this.customCovMatrix = cov;
        return this;
    }

    /**
     * Run the Monte Carlo VaR calculation over all supplied trades.
     *
     * @param trades list of trades (must each have spot &gt; 0).
     * @return {@link VaRResult} containing VaR at 95 %, 99 %, and 99.9 %.
     */
    public VaRResult calculate(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) {
            return new VaRResult(0, 0, 0);
        }

        // 1. Build sorted underlier universe ------------------------------- //
        List<String> underliers = trades.stream()
                .map(Trade::getUnderlier)
                .distinct()
                .sorted()
                .toList();
        int n = underliers.size();
        Map<String, Integer> idx = new HashMap<>(n);
        for (int i = 0; i < n; i++) idx.put(underliers.get(i), i);

        // 2. Build / validate covariance matrix ----------------------------- //
        DMatrixRMaj cov = buildCovMatrix(n);

        // 3. Cholesky decomposition  Σ = L·Lᵀ  ----------------------------- //
        DMatrixRMaj L = cholesky(cov, n);

        // 4. Compute each trade's dollar delta per underlier ---------------- //
        //    dollarDelta[i] = Σ_{trades on underlier i}  delta_j × qty_j × spot_j
        double[] dollarDelta = new double[n];
        BlackScholesPricer bsPricer = new BlackScholesPricer();
        for (Trade t : trades) {
            if (t.getSpot() <= 0) continue;
            var attrs = bsPricer.price(t);                  // delta already qty-scaled
            int i = idx.get(t.getUnderlier());
            // delta is qty-scaled; multiply by spot to convert to $ delta
            dollarDelta[i] += attrs.delta() * t.getSpot();
        }

        // 5. Monte Carlo P&L simulation ------------------------------------- //
        double[] pnl = new double[numScenarios];
        double[] epsilon = new double[n];
        double[] shock   = new double[n];

        for (int s = 0; s < numScenarios; s++) {
            // draw independent standard normals
            for (int i = 0; i < n; i++) epsilon[i] = rng.nextGaussian();

            // correlate: shock = L · ε
            for (int i = 0; i < n; i++) {
                double sum = 0.0;
                for (int j = 0; j <= i; j++) {
                    sum += L.get(i, j) * epsilon[j];
                }
                shock[i] = sum;
            }

            // portfolio P&L ≈ Σ dollarDelta_i × return_i
            double scenarioPnl = 0.0;
            for (int i = 0; i < n; i++) {
                scenarioPnl += dollarDelta[i] * shock[i];
            }
            pnl[s] = scenarioPnl;
        }

        // 6. Sort and read percentiles -------------------------------------- //
        Arrays.sort(pnl);

        // VaR is expressed as a positive loss (left-tail loss)
        double var95  = -quantile(pnl, 1.0 - PCTILE_95);
        double var99  = -quantile(pnl, 1.0 - PCTILE_99);
        double var999 = -quantile(pnl, 1.0 - PCTILE_999);

        return new VaRResult(var95, var99, var999);
    }

    // ------------------------------------------------------------------ //
    //  Private helpers
    // ------------------------------------------------------------------ //

    /**
     * Build a daily-return covariance matrix.
     * Uses the custom matrix if provided, otherwise constructs one from
     * {@code DEFAULT_DAILY_VOL} and {@code DEFAULT_CORRELATION}.
     */
    private DMatrixRMaj buildCovMatrix(int n) {
        DMatrixRMaj cov = new DMatrixRMaj(n, n);

        if (customCovMatrix != null) {
            if (customCovMatrix.length != n || customCovMatrix[0].length != n) {
                throw new IllegalArgumentException(
                        "Custom covariance matrix size " + customCovMatrix.length
                        + "×" + customCovMatrix[0].length
                        + " does not match underlier count " + n);
            }
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    cov.set(i, j, customCovMatrix[i][j]);
            return cov;
        }

        double variance = DEFAULT_DAILY_VOL * DEFAULT_DAILY_VOL;
        double covar    = variance * DEFAULT_CORRELATION;

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                cov.set(i, j, (i == j) ? variance : covar);
            }
        }
        return cov;
    }

    /**
     * Lower-triangular Cholesky factor of a symmetric positive-definite matrix.
     * Uses EJML's {@link CholeskyDecomposition_F64}.
     *
     * @param cov n×n covariance matrix (modified in place during decomposition)
     * @param n   dimension
     * @return lower-triangular factor L  such that  cov = L·Lᵀ
     */
    private static DMatrixRMaj cholesky(DMatrixRMaj cov, int n) {
        CholeskyDecomposition_F64<DMatrixRMaj> chol =
                DecompositionFactory_DDRM.chol(n, true);   // true = lower
        if (!chol.decompose(cov)) {
            throw new ArithmeticException(
                    "Covariance matrix is not positive-definite; "
                    + "Cholesky decomposition failed.");
        }
        return chol.getT(null);
    }

    /**
     * Linear-interpolation quantile on a pre-sorted array.
     *
     * @param sorted sorted (ascending) array of values
     * @param p      probability in [0, 1]
     * @return interpolated quantile
     */
    private static double quantile(double[] sorted, double p) {
        if (p <= 0) return sorted[0];
        if (p >= 1) return sorted[sorted.length - 1];
        double pos  = p * (sorted.length - 1);
        int    lo   = (int) pos;
        double frac = pos - lo;
        // return lower rank and upper rank interpolation
        return sorted[lo] * (1 - frac) + sorted[lo + 1] * frac;
    }

    // ------------------------------------------------------------------ //
    //  Result type
    // ------------------------------------------------------------------ //

    /**
     * Immutable VaR result at three confidence levels.
     * All values are expressed as <em>positive</em> dollar losses.
     */
    public record VaRResult(double var95, double var99, double var999) {

        /** 1-day VaR scaled to a {@code horizonDays}-day horizon via √T rule. */
        public VaRResult scale(int horizonDays) {
            double sqrtT = Math.sqrt(horizonDays);
            return new VaRResult(var95 * sqrtT, var99 * sqrtT, var999 * sqrtT);
        }

        @Override
        public String toString() {
            return "VaRResult{1d-95%%=%.2f, 1d-99%%=%.2f, 1d-99.9%%=%.2f}"
                    .formatted(var95, var99, var999);
        }
    }

    // ------------------------------------------------------------------ //
    //  main() – standalone smoke test
    // ------------------------------------------------------------------ //

    public static void main(String[] args) {
        BlackScholesPricer bs = new BlackScholesPricer();

        // Build a small portfolio: 3 underliers, 5 trades
        List<Trade> trades = new ArrayList<>();

        Trade t1 = new EquityTrade("T1", "AAPL", "EQUITIES", "TECH", "US", 100, "BUY", 170.0, 0.5);
        t1.setSpot(180.0); t1.setRiskFreeRate(0.05);

        Trade t2 = new EquityTrade("T2", "AAPL", "EQUITIES", "TECH", "US", 50, "SELL", 175.0, 0.25);
        t2.setSpot(180.0); t2.setRiskFreeRate(0.05);

        Trade t3 = new EquityTrade("T3", "MSFT", "EQUITIES", "TECH", "US", 200, "BUY", 400.0, 1.0);
        t3.setSpot(410.0); t3.setRiskFreeRate(0.05);

        Trade t4 = new EquityTrade("T4", "GOOG", "EQUITIES", "TECH", "US", 75, "BUY", 155.0, 0.75);
        t4.setSpot(160.0); t4.setRiskFreeRate(0.05);

        Trade t5 = new EquityTrade("T5", "GOOG", "EQUITIES", "TECH", "US", 30, "SELL", 165.0, 0.5);
        t5.setSpot(160.0); t5.setRiskFreeRate(0.05);

        trades.addAll(List.of(t1, t2, t3, t4, t5));

        VaRPricer pricer = new VaRPricer(50_000);
        VaRResult result = pricer.calculate(trades);

        System.out.println("=== 1-Day VaR ===");
        System.out.printf("  95%% : $%,.2f%n", result.var95());
        System.out.printf("  99%% : $%,.2f%n", result.var99());
        System.out.printf("99.9%% : $%,.2f%n", result.var999());

        VaRResult tenDay = result.scale(10);
        System.out.println("\n=== 10-Day VaR (√T scaled) ===");
        System.out.printf("  95%% : $%,.2f%n", tenDay.var95());
        System.out.printf("  99%% : $%,.2f%n", tenDay.var99());
        System.out.printf("99.9%% : $%,.2f%n", tenDay.var999());
    }
}
