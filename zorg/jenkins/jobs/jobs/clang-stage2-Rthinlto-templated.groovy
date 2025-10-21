//ToDo: This will go away with the global shared library
branchName = 'jadams/shared-library'

library identifier: "zorg-shared-lib@${branchName}",
            retriever: modernSCM([
                $class: 'GitSCMSource',
                remote: scm.userRemoteConfigs[0].url,
                credentialsId: scm.userRemoteConfigs[0].credentialsId
            ])

clangPipeline([
    jobName: 'clang-stage2-Rthinlto-templated',
    buildConfig: [
        cmake_type: "RelWithDebInfo",
        thinlto: true,
        projects: "clang;clang-tools-extra;compiler-rt",
        stage: 2,
        incremental: false,
        stage1Job: "clang-1-RA-templated"
    ],
    testConfig: [
        test_type: "testlong",
        test_timeout: 600
    ]
])