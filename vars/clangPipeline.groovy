import org.llvm.jenkins.ClangBuilder

def call(Map config = [:]) {
    // Validate required parameters
    if (!config.jobTemplate) {
        error("jobTemplate is required but was not provided in config. Please specify a jobTemplate (e.g., 'clang-stage2-Rthinlto')")
    }

    def builder = new ClangBuilder(this)
    def buildConfig = config.buildConfig ?: [:]
    def testConfig = config.testConfig ?: [:]
    def stagesToRun = config.stages ?: ['checkout', 'build', 'test']
    def jobTemplate = config.jobTemplate

    pipeline {
        options {
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'LABEL', defaultValue: params.LABEL ?: 'macos-x86_64', description: 'Node label to run on')
            string(name: 'GIT_SHA', defaultValue: params.GIT_REVISION ?: '*/main', description: 'Git commit to build.')
            string(name: 'ARTIFACT', defaultValue: params.ARTIFACT ?: 'llvm.org/clang-stage1-RA/latest', description: 'Clang artifact to use')
            string(name: 'BISECT_GOOD', defaultValue: params.BISECT_GOOD ?: '', description: 'Good commit for bisection')
            string(name: 'BISECT_BAD', defaultValue: params.BISECT_BAD ?: '', description: 'Bad commit for bisection')
            booleanParam(name: 'IS_BISECT_JOB', defaultValue: params.IS_BISECT_JOB ?: false, description: 'Whether clang is being built as part of a bisection job')
        }

        agent {
            node {
                label params.LABEL
            }
        }

        stages {
            stage('Validate Configuration') {
                steps {
                    script {
                        echo "✅ Configuration validated successfully"
                        echo "Job Template: ${jobTemplate}"
                        echo "Build Config: ${buildConfig}"
                        echo "Test Config: ${testConfig}"
                        echo "Stages to run: ${stagesToRun}"
                    }
                }
            }

            stage('Setup Build Description') {
                steps {
                    script {
                        def buildType = params.IS_BISECT_JOB ? "🔍 BISECTION TEST" : "🔧 NORMAL BUILD"
                        def commitInfo = params.GIT_SHA.take(8)

                        if (params.IS_BISECT_JOB && params.BISECT_GOOD && params.BISECT_BAD) {
                            def goodShort = params.BISECT_GOOD.take(8)
                            def badShort = params.BISECT_BAD.take(8)
                            currentBuild.description = "${buildType}: Testing ${commitInfo} (${goodShort}..${badShort})"
                        } else {
                            currentBuild.description = "${buildType}: ${commitInfo}"
                        }

                        echo "Build Type: ${buildType}"
                        echo "Job Template: ${jobTemplate}"
                    }
                }
            }

            stage('Checkout') {
                when {
                    expression { 'checkout' in stagesToRun }
                }
                steps {
                    script {
                        builder.checkoutStage()
                    }
                }
            }

            stage('Setup Venv') {
                when {
                    expression { 'checkout' in stagesToRun }
                }
                steps {
                    script {
                        builder.setupVenvStage()
                    }
                }
            }

            stage('Fetch Artifact') {
                when {
                    expression {
                        'build' in stagesToRun && (buildConfig.stage ?: 1) >= 2
                    }
                }
                steps {
                    script {
                        builder.fetchArtifactStage()
                    }
                }
            }

            stage('Build') {
                when {
                    expression { 'build' in stagesToRun }
                }
                steps {
                    script {
                        builder.buildStage(buildConfig)
                    }
                }
            }

            stage('Test') {
                when {
                    expression { 'test' in stagesToRun }
                }
                steps {
                    script {
                        builder.testStage(testConfig)
                    }
                }
            }
        }

        post {
            always {
                script {
                    builder.cleanupStage()
                }
            }
        }
    }
}
