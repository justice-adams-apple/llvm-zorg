def call(Map params) {
    def pythonScript = libraryResource('scripts/bisect.py')
    writeFile file: 'bisect.py', text: pythonScript
    sh 'chmod +x bisect.py'

    try {
        def command = params.command
        def args = params.args ?: []
        def repoPath = params.repoPath ?: '.'

        def cmdLine = "python3 bisect.py ${command}"
        if (repoPath != '.') {
            cmdLine += " --repo-path '${repoPath}'"
        }
        if (args) {
            cmdLine += " ${args.join(' ')}"
        }

        def result = sh(script: cmdLine, returnStdout: true).trim()

        try {
            return readJSON text: result
        } catch (Exception e) {
            return [output: result]
        }
    } finally {
        sh 'rm -f bisect.py'
    }
}

def initializeBisection(String goodCommit, String badCommit, String stateFile, String repoPath = '.') {
    return bisectionManager([
        command: 'init',
        args: [goodCommit, badCommit, stateFile],
        repoPath: repoPath
    ])
}

def recordResult(String commit, String result, String stateFile, String repoPath = '.') {
    return bisectionManager([
        command: 'record',
        args: [commit, result, stateFile],
        repoPath: repoPath
    ])
}

def getStatus(String stateFile, String repoPath = '.') {
    return bisectionManager([
        command: 'status',
        args: [stateFile],
        repoPath: repoPath
    ])
}
