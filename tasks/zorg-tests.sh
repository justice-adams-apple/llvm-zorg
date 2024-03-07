. "${TASKDIR}"/utils/venv.sh
. "${TASKDIR}"/utils/venv_lit.sh

mkdir -p result
cd "config/test/jenkins"
lit --xunit-xml-output="${WORKSPACE}/result/xunit.xml" -v .
