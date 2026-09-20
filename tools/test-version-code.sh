#!/usr/bin/env bash
# Validates the version code formula of the release workflow against VersionUtils.kt.
test_version() {
  VERSION="$1"
  CORE="${VERSION%%-*}"
  MAJOR=$(echo "$CORE" | cut -d '.' -f 1)
  MINOR=$(echo "$CORE" | cut -d '.' -f 2)
  PATCH=$(echo "$CORE" | cut -d '.' -f 3)
  PRE=$(echo "$VERSION" | sed -n 's/^[^-]*-[^.]*\.\([0-9]\{1,\}\)$/\1/p')
  echo "$VERSION -> $(( MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + ${PRE:-99} ))"
}

test_version 0.3.8
test_version 0.7.0
test_version 1.1.1
test_version 2.0.0
test_version 2.0.0-rc.3
test_version 99.99.99
test_version 99.99.99-rc.1
