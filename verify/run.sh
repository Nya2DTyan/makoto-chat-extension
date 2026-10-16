#!/usr/bin/env bash
# Стенды проверки — на СОБРАННОМ моде, а не на исходниках.
#
# Зачем так: большую часть поведения мода нельзя увидеть без запуска игры
# (геометрия полоски, затухание вместе с чатом, разбор форматов сервера,
# порядок вкладок). Стенды грузят готовый jar через URLClassLoader на
# classpath клиента и вызывают методы напрямую — то есть проверяют тот самый
# байткод, который получит игрок.
#
# Использование:
#   verify/run.sh            # цель по умолчанию — 26.2
#   verify/run.sh 26.3
#
# Перед запуском нужен собранный мод: ./gradlew build -Pmc=<версия>
set -euo pipefail

cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
export PATH="${JAVA_HOME}/bin:${PATH}"

MC="${1:-26.2}"
LIBS="build/${MC}/libs"
JAR="$(ls "${LIBS}"/makoto-chat-extension-*.jar 2>/dev/null | grep -v sources | head -1 || true)"
if [[ -z "${JAR}" ]]; then
	echo "Нет собранного мода для цели ${MC}. Сначала: ./gradlew build -Pmc=${MC}" >&2
	exit 1
fi

# Classpath клиента: Minecraft и Fabric — стендам нужны их классы, чтобы
# загрузить мод и вызвать его методы.
CP="$(./gradlew -q printClientClasspath -Pmc="${MC}" --no-daemon | tail -1)"
if [[ -z "${CP}" ]]; then
	echo "Не удалось получить classpath для цели ${MC}" >&2
	exit 1
fi

OUT="build/${MC}/verify-classes"
mkdir -p "${OUT}"
javac -nowarn -encoding UTF-8 -cp "${CP}" -d "${OUT}" verify/*.java

failed=0
for stand in VerifyLayout VerifyTabOrder VerifyServerFormat VerifyRouting; do
	echo "=== ${stand} (${MC}) ==="
	if ! java -cp "${OUT}:${CP}" "${stand}" "${JAR}"; then
		failed=1
	fi
done

if [[ ${failed} -ne 0 ]]; then
	echo "СТЕНДЫ НЕ ПРОШЛИ (${MC})" >&2
	exit 1
fi

echo "Все стенды прошли: ${JAR}"