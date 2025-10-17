@Library('your-shared-library') _

pipeline {
    agent any

    parameters {
        string(name: 'GOOD_COMMIT', description: 'Known good commit SHA')
        string(name: 'BAD_COMMIT', description: 'Known bad commit SHA')
        string(name: 'TEST_JOB_NAME', description: 'Job to test commits', defaultValue: 'llvm-build')
        string(name: 'SESSION_ID', description: 'Session ID (for restart)', defaultValue: '')
        booleanParam(name: 'DRY_RUN', defaultValue: false, description: 'Dry run mode')
    }

    environment {
        REPO_PATH = 'llvm-project'
    }

    stages {
        stage('Setup') {
            steps {
                dir(env.REPO_PATH) {
                    checkout scm
                }
            }
        }

        stage('Initialize') {
            steps {
                script {
                    bisectionManager.initializeBisection(
                        params.GOOD_COMMIT,
                        params.BAD_COMMIT,
                        params.TEST_JOB_NAME,
                        env.REPO_PATH,
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
                        def stepInfo = bisectionManager.logStepStart(stepNumber, env.REPO_PATH)

                        if (stepInfo.type == 'complete') {
                            echo "🎉 Bisection complete! Failing commit: ${stepInfo.failing_commit}"
                            break
                        }

                        // Show restart instructions
                        bisectionManager.showRestartInstructions(stepNumber, params.TEST_JOB_NAME, env.REPO_PATH)

                        if (params.DRY_RUN) {
                            def simulatedResult = (stepNumber % 2 == 0) ? "SUCCESS" : "FAILURE"
                            echo "🎭 Simulated result: ${simulatedResult}"
                            bisectionManager.recordTestResult(stepInfo.commit, simulatedResult, env.REPO_PATH)
                        } else {
                            // Execute real job
                            def startTime = System.currentTimeMillis()

                            try {
                                def jobResult = build(
                                    job: params.TEST_JOB_NAME,
                                    parameters: [
                                        string(name: 'GIT_SHA', value: stepInfo.commit),
                                        string(name: 'BISECT_SESSION_ID', value: stepInfo.session_id),
                                        string(name: 'BISECT_STEP', value: stepNumber.toString())
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
                                    env.REPO_PATH
                                )

                                // Record the test result
                                bisectionManager.recordTestResult(stepInfo.commit, jobResult.result, env.REPO_PATH)

                            } catch (Exception e) {
                                def duration = (System.currentTimeMillis() - startTime) / 1000.0

                                // Log the failure
                                bisectionManager.logJobFailure(
                                    params.TEST_JOB_NAME,
                                    e.message,
                                    duration,
                                    stepNumber,
                                    params.TEST_JOB_NAME,
                                    env.REPO_PATH
                                )

                                error("Infrastructure failure - see restart instructions above")
                            }
                        }

                        stepNumber++
                        sleep(2)
                    }
                }
            }
        }

        stage('Final Report') {
            steps {
                script {
                    bisectionManager.generateFinalReport(env.REPO_PATH)
                }
            }
        }
    }

    post {
        always {
            script {
                bisectionManager.displaySummary(env.REPO_PATH)
                archiveArtifacts artifacts: "bisection_state.json,bisection.log,restart_instructions.log,bisection_final_report.txt",
                                allowEmptyArchive: true
            }
        }
    }
}
