//ToDo: This will go away with the global shared library
branchName = 'jadams/shared-library'

library identifier: "zorg-shared-lib@${branchName}",
            retriever: modernSCM([
                $class: 'GitSCMSource',
                remote: scm.userRemoteConfigs[0].url,
                credentialsId: scm.userRemoteConfigs[0].credentialsId
            ])
// Now call clangPipeline directly (this becomes the only pipeline)
clangPipeline([
    stage: 2,
    jobName: 'clang-stage2-Rthinlto',
    buildConfig: [
        cmake_type: "RelWithDebInfo",
        thinlto: true,
        projects: "clang;clang-tools-extra;compiler-rt"
    ],
    testConfig: [
        test_type: "testlong",
        test_timeout: 600
    ]
])