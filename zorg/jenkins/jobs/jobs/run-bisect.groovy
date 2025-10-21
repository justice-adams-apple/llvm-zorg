//ToDo: This will go away with the global shared library
branchName = 'jadams/shared-library'

library identifier: "zorg-shared-lib@${branchName}",
            retriever: modernSCM([
                $class: 'GitSCMSource',
                remote: scm.userRemoteConfigs[0].url,
                credentialsId: scm.userRemoteConfigs[0].credentialsId
            ])

pipeline {
    agent {
        node {
            label 'macos-x86_64'
        }
    }

    parameters {
        string(name: 'GOOD_COMMIT', description: 'Known good commit SHA', defaultValue: '')
        string(name: 'BAD_COMMIT', description: 'Known bad commit SHA', defaultValue: '')
        string(name: 'TEST_JOB_NAME', description: 'Job to execute for testing each commit', defaultValue: 'llvm-build')
        choice(
            name: 'REPOSITORY',
            choices: ['llvm-project'],
            description: 'Repository to bisect'
        )
        string(name: 'SESSION_ID', description: 'Session ID to continue (optional)', defaultValue: '')
        booleanParam(name: 'DRY_RUN', defaultValue: false, description: 'Dry run mode')
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.GOOD_COMMIT || !params.BAD_COMMIT) {
                        error("Both GOOD_COMMIT and BAD_COMMIT parameters are required")
                    }
                    if (!params.TEST_JOB_NAME) {
                        error("TEST_JOB_NAME parameter is required")
                    }
                }
            }
        }

        stage('Setup Repository') {
            steps {
                script {
                    echo "📁 Setting up repository: ${params.REPOSITORY}..."

                    dir(params.REPOSITORY) {
                        // Clone or checkout the repository
                        checkout([$class: 'GitSCM', branches: [
                            [name: "main"]
                        ], extensions: [
                            [$class: 'CloneOption',
                            timeout: 30]
                        ], userRemoteConfigs: [
                            [url: 'https://github.com/llvm/llvm-project.git']
                        ]])

                        // Verify commits exist
                        sh "git cat-file -e ${params.GOOD_COMMIT}"
                        sh "git cat-file -e ${params.BAD_COMMIT}"

                        echo "✅ Repository setup complete"
                    }
                }
            }
        }

        stage('Initialize Bisection') {
            steps {
                script {
                    bisectionManager.initializeBisection(
                        params.GOOD_COMMIT,
                        params.BAD_COMMIT,
                        params.TEST_JOB_NAME,
                        params.REPOSITORY,
                        params.SESSION_ID ?: null
                    )
                }
            }
        }

        stage('Execute Bisection') {
            steps {
                script {
                    def stepNumber = 1
                    def maxSteps = 50

                    while (stepNumber <= maxSteps) {
                        // Log step and get info
                        def stepInfo = bisectionManager.logStepStart(stepNumber, params.REPOSITORY)

                        if (stepInfo.type == 'complete') {
                            echo "🎉 Bisection complete! Failing commit: ${stepInfo.failing_commit}"
                            break
                        }

                        // Show restart instructions
                        bisectionManager.showRestartInstructions(stepNumber, params.TEST_JOB_NAME, params.REPOSITORY)

                        if (params.DRY_RUN) {
                            def simulatedResult = (stepNumber % 2 == 0) ? "SUCCESS" : "FAILURE"
                            echo "🎭 Simulated result: ${simulatedResult}"
                            bisectionManager.recordTestResult(stepInfo.commit, simulatedResult, params.REPOSITORY)
                        } else {
                            // Execute real job
                            def startTime = System.currentTimeMillis()

                            // ToDo: Trigger the job we need
                            def jobResult = build(
                                job: params.TEST_JOB_NAME,
                                parameters: [
                                    string(name: 'GIT_SHA', value: stepInfo.commit),
                                    string(name: 'BISECT_SESSION_ID', value: stepInfo.session_id),
                                    string(name: 'BISECT_STEP', value: stepNumber.toString()),
                                    string(name: 'REPOSITORY', value: params.REPOSITORY)
                                ],
                                propagate: false,
                                wait: true
                            )

                            def duration = (System.currentTimeMillis() - startTime) / 1000.0

                            // Log the job execution
                            bisectionManager.logJobExecution(
                                params.TEST_JOB_NAME,
                                jobResult.result,
                                duration,
                                jobResult.absoluteUrl,
                                jobResult.number.toString(),
                                params.REPOSITORY
                            )

                            // Record the test result
                            bisectionManager.recordTestResult(stepInfo.commit, jobResult.result, params.REPOSITORY)
                        }

                        stepNumber++
                        sleep(2)
                    }

                    if (stepNumber > maxSteps) {
                        error("Bisection exceeded maximum steps")
                    }
                }
            }
        }

        stage('Final Report') {
            steps {
                script {
                    bisectionManager.generateFinalReport(params.REPOSITORY)
                }
            }
        }
    }

    post {
        always {
            script {
                bisectionManager.displaySummary(params.REPOSITORY)
                archiveArtifacts artifacts: "bisection_state.json,bisection.log,restart_instructions.log,bisection_final_report.txt",
                                allowEmptyArchive: true
            }
        }
        cleanup {
            // Clean up but preserve artifacts
            sh 'rm -f bisection_manager.py'
        }
    }
}
