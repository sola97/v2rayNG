#!/usr/bin/env bash

set -euo pipefail

if [[ $# -eq 0 ]]; then
    echo "At least one Gradle task is required" >&2
    exit 2
fi

readonly max_attempts=3
readonly log_file=".gradle-ci-attempt.log"
readonly transient_network_pattern='SSL peer shut down incorrectly|Remote host terminated the handshake|Connection reset|Read timed out|Connection timed out|Network is unreachable|Temporary failure in name resolution|UnknownHostException|ConnectException|status code (502|503|504)'

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    available_kb="$(df -Pk . | awk 'NR == 2 {print $4}')"
    minimum_kb=$((4 * 1024 * 1024))
    if ((available_kb < minimum_kb)); then
        echo "Gradle requires at least 4 GiB free; available: $((available_kb / 1024 / 1024)) GiB" >&2
        exit 20
    fi

    set +e
    ./gradlew \
        --no-daemon \
        --stacktrace \
        --max-workers=1 \
        -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8' \
        -Pkotlin.compiler.execution.strategy=in-process \
        "$@" 2>&1 | tee "$log_file"
    gradle_status=${PIPESTATUS[0]}
    set -e

    if [[ $gradle_status -eq 0 ]]; then
        rm -f "$log_file"
        exit 0
    fi

    if ! grep -Eiq "$transient_network_pattern" "$log_file"; then
        exit "$gradle_status"
    fi

    if [[ $attempt -eq $max_attempts ]]; then
        echo "Gradle failed after ${max_attempts} transient network attempts" >&2
        exit "$gradle_status"
    fi

    echo "Gradle dependency download failed transiently; retrying attempt $((attempt + 1))/${max_attempts}" >&2
    sleep $((attempt * 5))
done
