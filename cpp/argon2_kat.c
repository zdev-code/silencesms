#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "argon2.h"

int main(void) {
    uint8_t password[32];
    uint8_t salt[16];
    uint8_t secret[8];
    uint8_t associated_data[12];
    uint8_t output[32];
    const uint8_t expected[32] = {
        0x0d, 0x64, 0x0d, 0xf5, 0x8d, 0x78, 0x76, 0x6c,
        0x08, 0xc0, 0x37, 0xa3, 0x4a, 0x8b, 0x53, 0xc9,
        0xd0, 0x1e, 0xf0, 0x45, 0x2d, 0x75, 0xb6, 0x5e,
        0xb5, 0x25, 0x20, 0xe9, 0x6b, 0x01, 0xe6, 0x59
    };

    memset(password, 0x01, sizeof(password));
    memset(salt, 0x02, sizeof(salt));
    memset(secret, 0x03, sizeof(secret));
    memset(associated_data, 0x04, sizeof(associated_data));

    argon2_context context = {
        output, sizeof(output), password, sizeof(password), salt, sizeof(salt),
        secret, sizeof(secret), associated_data, sizeof(associated_data),
        3, 32, 4, 4, ARGON2_VERSION_13, NULL, NULL,
        ARGON2_FLAG_CLEAR_PASSWORD | ARGON2_FLAG_CLEAR_SECRET
    };

    int result = argon2_ctx(&context, Argon2_id);
    if (result != ARGON2_OK) {
        fprintf(stderr, "Argon2id failed: %s\n", argon2_error_message(result));
        return 1;
    }
    if (memcmp(output, expected, sizeof(output)) != 0) {
        fputs("RFC 9106 Argon2id vector mismatch\n", stderr);
        return 2;
    }

    puts("RFC9106_ARGON2ID_OK");
    return 0;
}