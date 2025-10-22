def call(Map config = [:]) {
    def pythonScript = libraryResource('scripts/artifact_manager.py')
    writeFile file: 'artifact_manager.py', text: pythonScript
    sh 'chmod +x artifact_manager.py'

    withEnv(["PATH+EXTRA=/usr/bin:/usr/local/bin"]) {
        withCredentials([string(credentialsId: 's3_resource_bucket', variable: 'S3_BUCKET')]) {
            def jobName = env.JOB_NAME
            def isBisectionJob = params.BISECT == 'true'

            // Determine artifact parameter
            def artifactParam = isBisectionJob ? null : params.ARTIFACT

            // Call Python script to handle artifact logic
            def pythonCmd = """
                source ./venv/bin/activate
                python ./artifact_manager.py \\
                    --job-name "${jobName}" \\
                    --workspace "\$WORKSPACE" \\
                    --output-file artifact_result.properties
            """

            if (artifactParam) {
                pythonCmd += " --artifact \"${artifactParam}\""
            }

            def scriptResult = sh(
                script: pythonCmd,
                returnStatus: true
            )

            // Read results from Python script
            def resultProps = readProperties file: 'artifact_result.properties'
            def artifactFound = resultProps.ARTIFACT_FOUND == 'true'
            def usedArtifact = resultProps.USED_ARTIFACT
            def needsStage1 = resultProps.NEEDS_STAGE1 == 'true'

            if (needsStage1) {
                echo "Triggering stage 1 build for artifact: ${usedArtifact}"

                // Trigger stage 1 job and wait for completion
                def stage1Build = build(
                    job: config.stage1Job,
                    parameters: [
                        string(name: 'GIT_SHA', value: params.GIT_SHA),
                        string(name: 'BISECT_GOOD', value: params.BISECT_GOOD),
                        string(name: 'BISECT_BAD', value: params.BISECT_BAD),
                        booleanParam(name: 'IS_BISECT_JOB', value: true),
                        booleanParam(name: 'SKIP_TESTS', value: true),
                    ],
                    wait: true,
                    propagate: true
                )

                echo "Stage 1 build completed successfully. Build number: ${stage1Build.number}"

                // Retry fetching the artifact after stage 1 completes
                def retryCmd = """
                    source ./venv/bin/activate
                    export ARTIFACT="${usedArtifact}"
                    echo "ARTIFACT=\$ARTIFACT"
                    python llvm-zorg/zorg/jenkins/monorepo_build.py fetch
                    ls \$WORKSPACE/host-compiler/lib/clang/
                    VERSION=`ls \$WORKSPACE/host-compiler/lib/clang/`
                """

                sh retryCmd
            }

            // Return useful information
            return [
                artifactFound: artifactFound,
                usedArtifact: usedArtifact,
                needsStage1: needsStage1,
                stage1Triggered: needsStage1
            ]
        }
    }
}
