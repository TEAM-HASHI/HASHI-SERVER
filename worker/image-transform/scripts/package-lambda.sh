#!/usr/bin/env bash
set -euo pipefail

WORKER_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY_ROOT="$(cd "${WORKER_ROOT}/../.." && pwd)"
ARTIFACT_ROOT="${WORKER_ROOT}/artifacts"
PACKAGE_ROOT="${ARTIFACT_ROOT}/package"
ZIP_PATH="${ARTIFACT_ROOT}/image-transform.zip"

if [[ "${ARTIFACT_ROOT}" != "${WORKER_ROOT}/artifacts" ]]; then
  echo "Unexpected artifact path" >&2
  exit 1
fi

rm -rf -- "${ARTIFACT_ROOT}"
mkdir -p "${PACKAGE_ROOT}"

cd "${WORKER_ROOT}"
npm run build

cp package.json package-lock.json "${PACKAGE_ROOT}/"
cp -R dist "${PACKAGE_ROOT}/dist"
cp -R "${REPOSITORY_ROOT}/media-specs" "${PACKAGE_ROOT}/media-specs"

npm ci --omit=dev --prefix "${PACKAGE_ROOT}"

if [[ -d "${PACKAGE_ROOT}/node_modules/typescript" ]]; then
  echo "Production package unexpectedly contains TypeScript" >&2
  exit 1
fi

(
  cd "${PACKAGE_ROOT}"
  zip -q -r "${ZIP_PATH}" .
)

echo "Created ${ZIP_PATH}"
