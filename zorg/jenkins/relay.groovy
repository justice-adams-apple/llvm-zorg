#!/usr/bin/env groovy
@NonCPS
private def get_matching_jobs(pattern) {
    def jobs = []
    for (job in Jenkins.getInstance().getAllItems(Job)) {
        def jobname = job.getName()
        def m = jobname =~ pattern
        if (m) {
            def shortname = m[0][1]
            jobs.push([shortname, jobname])
        }
    }
    return jobs
}

private def basename(path) {
    return path.drop(path.lastIndexOf('/') + 1)
}

private def relay_steps(job_pattern, artifact_url, last_good_properties_url) {
    // The upstream jobs triggering the relay produce a
    // "last_good_build.properties" file that contains a reference to the
    // compiler artifact that should be used for this run and which llvm
    // revision it is based on.
    // Ensure you have the AWS CLI on path before triggering the relay
    withCredentials([string(credentialsId: 's3_resource_bucket', variable: 'S3_BUCKET')]) {
        propfile = basename(last_good_properties_url)
        sh """
        rm -f ${propfile}Relay - Clang Stage 2: cmake, R -g + <Sanitizers>
        aws s3 cp $S3_BUCKET/clangci/${last_good_properties_url} ${propfile}
        """
    }

    def props = readProperties file: propfile
    def artifact = props.ARTIFACT
    currentBuild.setDisplayName("${props.GIT_DISTANCE}-${props.GIT_SHA}")

    // Trigger all jobs with names matching the `job_pattern` regex.
    def joblist = get_matching_jobs(job_pattern)
    def parallel_builds = [:]
    for (j in joblist) {
        def shortname = j[0]
        def jobname = j[1]
        parallel_builds[shortname] = {
            def job_params = [
                [$class: 'StringParameterValue',
                 name: 'ARTIFACT',
                 value: artifact],
                [$class: 'StringParameterValue',
                 name: 'GIT_SHA',
                 value: props.GIT_SHA],
                [$class: 'StringParameterValue',
                 name: 'GIT_DISTANCE',
                 value: props.GIT_DISTANCE],
            ]
            build job: jobname, parameters: job_params
        }
    }
    parallel parallel_builds
}

def pipeline(job_pattern,
        artifact_url='clang-stage1-RA/latest',
        last_good_properties_url='clang-stage1-RA/last_good_build.properties') {
    //ToDo: Do we want to set up trigger specific nodes for this
    node('LLVM') {
        stage('main') {
            // Download aws CLI used to gather artifacts
            sh """
              rm -rf venv
              xcrun python3 -m venv venv
              set +u
              source ./venv/bin/activate
              pip install awscli
              set -u
            """
            withEnv(["PATH=$PATH:/usr/bin:/usr/local/bin:$WORKSPACE/venv/bin"]) {
                relay_steps job_pattern, artifact_url, last_good_properties_url
            }
        }
    }
}

def lldb_pipeline(job_pattern,
        artifact_url='http://green-dragon-21.local/artifacts/',
        last_good_properties_url='http://green-dragon-21.local/artifacts/lldb-cmake/last_good_build.properties') {
    node('master') {
        stage('main') {
            relay_steps job_pattern, artifact_url, last_good_properties_url
        }
    }
}

return this
