#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "argon2.h"

static void secure_zero(void *data, size_t length) {
    volatile uint8_t *cursor = (volatile uint8_t *)data;
    while (length-- > 0) *cursor++ = 0;
}

static void throw_argon2_exception(JNIEnv *env, int error_code) {
    jclass exception_class = (*env)->FindClass(
        env, "org/smssecure/smssecure/crypto/Argon2Exception");
    if (exception_class != NULL) {
        (*env)->ThrowNew(env, exception_class, argon2_error_message(error_code));
    }
}

JNIEXPORT jbyteArray JNICALL
Java_org_smssecure_smssecure_crypto_Argon2id_deriveNative(
    JNIEnv *env, jclass clazz, jbyteArray password, jbyteArray salt,
    jint memory_kib, jint iterations, jint parallelism, jint output_length) {
    (void)clazz;

    jsize password_length = (*env)->GetArrayLength(env, password);
    jsize salt_length = (*env)->GetArrayLength(env, salt);
    uint8_t *password_bytes = (uint8_t *)malloc((size_t)password_length);
    uint8_t *salt_bytes = (uint8_t *)malloc((size_t)salt_length);
    if ((password_length > 0 && password_bytes == NULL) || salt_bytes == NULL) {
        free(password_bytes);
        free(salt_bytes);
        throw_argon2_exception(env, ARGON2_MEMORY_ALLOCATION_ERROR);
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, password, 0, password_length,
                              (jbyte *)password_bytes);
    (*env)->GetByteArrayRegion(env, salt, 0, salt_length, (jbyte *)salt_bytes);
    if ((*env)->ExceptionCheck(env)) {
        secure_zero(password_bytes, (size_t)password_length);
        free(password_bytes);
        free(salt_bytes);
        return NULL;
    }

    uint8_t *output = (uint8_t *)malloc((size_t)output_length);
    if (output == NULL) {
        secure_zero(password_bytes, (size_t)password_length);
        free(password_bytes);
        free(salt_bytes);
        throw_argon2_exception(env, ARGON2_MEMORY_ALLOCATION_ERROR);
        return NULL;
    }

    int result = argon2id_hash_raw((uint32_t)iterations, (uint32_t)memory_kib,
                                   (uint32_t)parallelism, password_bytes,
                                   (size_t)password_length, salt_bytes,
                                   (size_t)salt_length, output,
                                   (size_t)output_length);

    secure_zero(password_bytes, (size_t)password_length);
    free(password_bytes);
    free(salt_bytes);

    if (result != ARGON2_OK) {
        secure_zero(output, (size_t)output_length);
        free(output);
        throw_argon2_exception(env, result);
        return NULL;
    }

    jbyteArray derived = (*env)->NewByteArray(env, output_length);
    if (derived != NULL) {
        (*env)->SetByteArrayRegion(env, derived, 0, output_length,
                                  (const jbyte *)output);
    }
    secure_zero(output, (size_t)output_length);
    free(output);
    return derived;
}