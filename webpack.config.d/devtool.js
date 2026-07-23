// Copyright (c) 2026, Potaty
// Keep development bundles compatible with the production-like script-src 'self' CSP.
// Webpack's default eval source maps require unsafe-eval, which would weaken token isolation.
config.devtool = 'source-map';
