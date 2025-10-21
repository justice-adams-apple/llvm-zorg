package org.llvm.jenkins

class ClangBuilder implements Serializable {
    def script

    ClangBuilder(script) {
        this.script = script
    }

    def checkoutStage() {
        script.dir('llvm-project') {
            script.checkout([
                $class: 'GitSCM',
                branches: [[name: script.params.GIT_SHA]],
                extensions: [[$class: 'CloneOption', timeout: 30]],
                userRemoteConfigs: [[url: 'https://github.com/llvm/llvm-project.git']]
            ])
        }
        script.dir('llvm-zorg') {
            script.checkout([
                $class: 'GitSCM',
                branches: [[name: '*/main']],
                extensions: [[$class: 'CloneOption', reference: '/Users/Shared/llvm-zorg.git']],
                userRemoteConfigs: [[url: 'https://github.com/llvm/llvm-zorg.git']]
            ])
        }
    }

    def setupVenvStage() {
        script.withEnv(["PATH+EXTRA=/usr/bin:/usr/local/bin"]) {
            script.sh '''
                rm -rf clang-*.tar.gz
                rm -rf venv
                python3 -m venv venv
                set +u
                source ./venv/bin/activate
                pip install -r ./llvm-zorg/zorg/jenkins/jobs/requirements.txt
                set -u
            '''
        }
    }

def fetchArtifactStage(config = [:]) {
    script.withEnv(["PATH+EXTRA=/usr/bin:/usr/local/bin"]) {
        script.withCredentials([script.string(credentialsId: 's3_resource_bucket', variable: 'S3_BUCKET')]) {
            def isBisectionJob = script.params.BISECT == 'true'
            def bisectionJobSuffix = "_bisect" // ToDo: Change this eventually

            if (isBisectionJob) {
                script.echo "This is a bisection job. Using git information to try and find artifact"
                // If this is a bisection job, we need to see if the stage 1 artifact exists from either
                // the bisection version of the job, or the regular CI job. If neither exists, we build stage1 and wait

                script.sh """
                    source ./venv/bin/activate
                    cd llvm-project
                    git tag -a -m "First Commit" first_commit 97724f18c79c7cc81ced24239eb5e883bf1398ef || true
                    git_desc=\$(git describe --match "first_commit")
                    export GIT_DISTANCE=\$(echo \${git_desc} | cut -f 2 -d "-")
                    sha=\$(echo \${git_desc} | cut -f 3 -d "-")
                    export GIT_SHA=\${sha:1}
                    cd -
                    echo "GIT_DISTANCE=\$GIT_DISTANCE" > git_info.properties
                    echo "GIT_SHA=\$GIT_SHA" >> git_info.properties
                """

                def gitProps = script.readProperties file: 'git_info.properties'
                def gitDistance = gitProps.GIT_DISTANCE
                def gitSha = gitProps.GIT_SHA
                def jobName = script.env.JOB_NAME

                // Construct both artifact names
                def primaryArtifact = "${jobName}/clang-d${gitDistance}-g${gitSha}.tar.gz"
                def bisectArtifact = "${jobName}${bisectionJobSuffix}/clang-d${gitDistance}-g${gitSha}.tar.gz"

                script.echo "Checking for primary artifact: ${primaryArtifact}"
                script.echo "Checking for bisect artifact: ${bisectArtifact}"

                // Check primary artifact first
                def primaryFetchResult = script.sh(
                    script: """
                        source ./venv/bin/activate
                        export ARTIFACT="${primaryArtifact}"
                        echo "ARTIFACT=\$ARTIFACT"
                        python llvm-zorg/zorg/jenkins/monorepo_build.py fetch
                    """,
                    returnStatus: true
                )

                def artifactFound = false
                def usedArtifact = ""

                if (primaryFetchResult == 0) {
                    script.echo "Primary artifact found: ${primaryArtifact}"
                    artifactFound = true
                    usedArtifact = primaryArtifact
                } else {
                    script.echo "Primary artifact not found. Checking bisect artifact: ${bisectArtifact}"

                    // Check bisect artifact
                    def bisectFetchResult = script.sh(
                        script: """
                            source ./venv/bin/activate
                            export ARTIFACT="${bisectArtifact}"
                            echo "ARTIFACT=\$ARTIFACT"
                            python llvm-zorg/zorg/jenkins/monorepo_build.py fetch
                        """,
                        returnStatus: true
                    )

                    if (bisectFetchResult == 0) {
                        script.echo "Bisect artifact found: ${bisectArtifact}"
                        artifactFound = true
                        usedArtifact = bisectArtifact
                    }
                }

                if (!artifactFound) {
                    script.echo "Neither artifact found. Triggering stage 1 build..."

                    // Trigger stage 1 job and wait for completion
                    def stage1Build = script.build(
                        job: config.stage1Job
                        parameters: [
                            script.string(name: 'GIT_SHA', value: script.params.GIT_SHA),
                            script.string(name: 'BISECT_GOOD', value: script.params.BISECT_GOOD),
                            script.string(name: 'BISECT_BAD', value: script.params.BISECT_BAD),
                            script.booleanParam(name: 'IS_BISECT_JOB', value: true),
                        ],
                        wait: true,
                        propagate: true
                    )

                    script.echo "Stage 1 build completed successfully. Build number: ${stage1Build.number}"

                    // Retry fetching the bisection job artifact after stage 1 completes
                    script.sh """
                        source ./venv/bin/activate
                        export ARTIFACT="${bisectArtifact}"
                        echo "ARTIFACT=\$ARTIFACT"
                        python llvm-zorg/zorg/jenkins/monorepo_build.py fetch
                        ls \$WORKSPACE/host-compiler/lib/clang/
                        VERSION=`ls \$WORKSPACE/host-compiler/lib/clang/`
                    """
                } else {
                    script.echo "Using artifact: ${usedArtifact}"
                    script.sh """
                        ls \$WORKSPACE/host-compiler/lib/clang/
                        VERSION=`ls \$WORKSPACE/host-compiler/lib/clang/`
                    """
                }
            } else {
                script.sh """
                    source ./venv/bin/activate
                    echo "ARTIFACT=${script.params.ARTIFACT}"
                    python llvm-zorg/zorg/jenkins/monorepo_build.py fetch
                    ls \$WORKSPACE/host-compiler/lib/clang/
                    VERSION=`ls \$WORKSPACE/host-compiler/lib/clang/`
                """
            }
        }
    }
}

    def buildStage(config = [:]) {
        def thinlto = config.thinlto ?: false
        def cmakeType = config.cmake_type ?: "RelWithDebInfo"
        def projects = config.projects ?: "clang;clang-tools-extra;compiler-rt"
        def runtimes = config.runtimes ?: ""
        def sanitizer = config.sanitizer ?: ""
        def assertions = config.assertions ?: false
        def timeout = config.timeout ?: 120
        def buildTarget = config.build_target ?: ""
        def noinstall = config.noinstall ?: false
        def extraCmakeFlags = config.cmake_flags ?: []
        def stage1Mode = config.stage1 ?: false
        def incremental = config.stage1 ?: false
        def extraEnvVars = config.env_vars ?: [:]

        // Build environment variables map
        def envVars = [
            "PATH+EXTRA": "/usr/bin:/usr/local/bin",
            "MACOSX_DEPLOYMENT_TARGET": stage1Mode ? "13.6" : null
        ]

        // Add custom environment variables
        extraEnvVars.each { key, value ->
            envVars[key] = value
        }

        // Filter out null values
        envVars = envVars.findAll { k, v -> v != null }
        def envList = envVars.collect { k, v -> "${k}=${v}" }

        script.withEnv(envList) {
            script.timeout(timeout) {
                script.withCredentials([script.string(credentialsId: 's3_resource_bucket', variable: 'S3_BUCKET')]) {
                    def buildCmd = buildMonorepoBuildCommand(config)

                    script.sh """
                        set -u
                        ${stage1Mode ? 'rm -rf build.properties' : ''}
                        source ./venv/bin/activate
                        cd llvm-project
                        git tag -a -m "First Commit" first_commit 97724f18c79c7cc81ced24239eb5e883bf1398ef || true
                        git_desc=\$(git describe --match "first_commit")
                        export GIT_DISTANCE=\$(echo \${git_desc} | cut -f 2 -d "-")
                        sha=\$(echo \${git_desc} | cut -f 3 -d "-")
                        export GIT_SHA=\${sha:1}
                        ${stage1Mode ? 'export LLVM_REV=\$(git show -q | grep "llvm-svn:" | cut -f2 -d":" | tr -d " ")' : ''}
                        cd -
                        ${stage1Mode ? 'echo "GIT_DISTANCE=\$GIT_DISTANCE" > build.properties' : ''}
                        ${stage1Mode ? 'echo "GIT_SHA=\$GIT_SHA" >> build.properties' : ''}
                        echo "ARTIFACT=\$JOB_NAME/clang-d\$GIT_DISTANCE-g\$GIT_SHA.tar.gz" >> build.properties'
                        ${incremental ? 'rm -rf clang-build clang-install *.tar.gz' : ''}
                        ${buildCmd}
                    """
                }
            }
        }
    }

    def buildMonorepoBuildCommand(config) {
        def testCommand = config.test_command ?: "cmake"
        def projects = config.projects ?: "clang;clang-tools-extra;compiler-rt"
        def runtimes = config.runtimes ?: ""
        def cmakeType = config.cmake_type ?: "RelWithDebInfo"
        def assertions = config.assertions ?: false
        def timeout = config.timeout ?: 120
        def buildTarget = config.build_target ?: ""
        def noinstall = config.noinstall ?: false
        def thinlto = config.thinlto ?: false
        def sanitizer = config.sanitizer ?: ""
        def extraCmakeFlags = config.cmake_flags ?: []

        def cmd = "python llvm-zorg/zorg/jenkins/monorepo_build.py ${testCommand} build"

        if (cmakeType != "default") {
            cmd += " --cmake-type=${cmakeType}"
        }

        cmd += " --projects=\"${projects}\""

        if (runtimes) {
            cmd += " --runtimes=\"${runtimes}\""
        }

        if (assertions) {
            cmd += " --assertions"
        }

        if (timeout != 2400) {
            cmd += " --timeout=${timeout}"
        }

        if (buildTarget) {
            cmd += " --cmake-build-target=${buildTarget}"
        }

        if (noinstall) {
            cmd += " --noinstall"
        }

        def cmakeFlags = []
        cmakeFlags.add("-DPython3_Executable=\$(which python)")

        if (thinlto) {
            cmakeFlags.add("-DLLVM_ENABLE_LTO=Thin")
        }

        if (sanitizer) {
            cmakeFlags.add("-DLLVM_USE_SANITIZER=${sanitizer}")
        }

        if (sanitizer == "Thread") {
            cmakeFlags.add("-DDYLD_LIBRARY_PATH=\$DYLD_LIBRARY_PATH")
        }

        cmakeFlags.addAll(extraCmakeFlags)

        cmakeFlags.each { flag ->
            cmd += " --cmake-flag=\"${flag}\""
        }

        return cmd
    }

    def testStage(config = [:]) {
        def testCommand = config.test_command ?: "cmake"
        def testType = config.test_type ?: "testlong"
        def testTargets = config.test_targets ?: []
        def timeout = config.test_timeout ?: 420
        def extraEnvVars = config.env_vars ?: [:]

        def envVars = ["PATH": "$PATH:/usr/bin:/usr/local/bin"]
        extraEnvVars.each { key, value ->
            envVars[key] = value
        }

        def envList = envVars.collect { k, v -> "${k}=${v}" }

        script.withEnv(envList) {
            script.timeout(timeout) {
                def cmd = "python llvm-zorg/zorg/jenkins/monorepo_build.py ${testCommand} ${testType}"

                testTargets.each { target ->
                    cmd += " --cmake-test-target=${target}"
                }

                script.sh """
                    set -u
                    source ./venv/bin/activate
                    rm -rf clang-build/testresults.xunit.xml
                    ${cmd}
                """
            }
        }
    }

    def cleanupStage() {
        script.sh "rm -rf clang-build clang-install host-compiler *.tar.gz"
    }
}
