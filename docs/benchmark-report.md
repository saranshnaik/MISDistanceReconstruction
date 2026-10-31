# Benchmark: 10,000 synthetic trips

Machine: Windows 11 10.0, AMD64 Family 25 Model 117 Stepping 2, AuthenticAMD, 16 logical cores, 15,661 MB RAM, JVM Java HotSpot(TM) 64-Bit Server VM 27.0.1.0.3, max heap 3,916 MB

Average pings per trip: 1,523. Seed 42, 40 shared routes.

| scenario | wall time | trips/s | engine p50 | engine p99 | engine mean | peak heap |
|---|---|---|---|---|---|---|
| cache OFF, 1 thread | 11.3 s | 888 | 0.93 ms | 1.78 ms | 0.94 ms | 124 MB |
| cache ON,  1 thread | 14.6 s | 684 | 1.29 ms | 2.14 ms | 1.28 ms | 119 MB |
| cache ON, 16 threads | 1.5 s | 6,627 | 1.50 ms | 9.00 ms | 1.92 ms | 860 MB |

| scenario | gaps needing a route | provider calls | cache hits | est. provider wait at 30 ms/call |
|---|---|---|---|---|
| cache OFF, 1 thread | 46,741 | 46,741 | 0 | 1,402 s |
| cache ON,  1 thread | 46,741 | 108 | 36,222 | 3 s |
| cache ON, 16 threads | 46,741 | 107 | 36,222 | 3 s |

Provider calls before caching: 46,741. After caching and batching: 108 (99.8% fewer).
Total distance, cache on, 1 thread vs 16 threads: 266,325,700,467 mm vs 266,325,700,467 mm (identical).
