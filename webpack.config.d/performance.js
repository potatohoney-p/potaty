// Copyright (c) 2026, Potaty
// Kotlin/JS includes its runtime in the main chunk. Webpack sees only the raw file, while Gradle's
// checkProductionBundleBudget enforces the tighter 320 KiB gzip transfer budget after this build.
config.performance = {
    ...config.performance,
    hints: 'warning',
    maxAssetSize: 1100000,
    maxEntrypointSize: 1100000,
};
