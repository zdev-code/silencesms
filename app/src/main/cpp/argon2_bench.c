#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "argon2.h"

#define MEMORY_KIB 65536
#define ITERATIONS 3
#define PARALLELISM 4
#define RUNS 3

static double elapsed_milliseconds(const struct timespec *start,
                                   const struct timespec *finish) {
    return (finish->tv_sec - start->tv_sec) * 1000.0 +
           (finish->tv_nsec - start->tv_nsec) / 1000000.0;
}

int main(void) {
    uint8_t password[16];
    uint8_t salt[16];
    uint8_t output[32];
    double total = 0.0;

    memset(password, 0x01, sizeof(password));
    memset(salt, 0x02, sizeof(salt));

    for (int run = 0; run < RUNS; run++) {
        struct timespec start;
        struct timespec finish;
        clock_gettime(CLOCK_MONOTONIC, &start);
        int result = argon2id_hash_raw(ITERATIONS, MEMORY_KIB, PARALLELISM,
                                       password, sizeof(password), salt,
                                       sizeof(salt), output, sizeof(output));
        clock_gettime(CLOCK_MONOTONIC, &finish);
        if (result != ARGON2_OK) {
            fprintf(stderr, "Argon2id failed: %s\n", argon2_error_message(result));
            return 1;
        }
        double elapsed = elapsed_milliseconds(&start, &finish);
        total += elapsed;
        printf("run_%d_ms=%.2f\n", run + 1, elapsed);
    }

    printf("ARGON2_BENCH_OK memory_kib=%d iterations=%d parallelism=%d average_ms=%.2f\n",
           MEMORY_KIB, ITERATIONS, PARALLELISM, total / RUNS);
    memset(password, 0, sizeof(password));
    memset(output, 0, sizeof(output));
    return 0;
}