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
        build_type: "clang",
        cmake_type: "RelWithDebInfo",
        thinlto: true,
        projects: "clang;compiler-rt",
        runtimes: "libunwind",
        stage: 2,
        timeout: 1200,
        incremental: false,
        stage1Job: "clang-stage1-RA-templated",
        cmake_flags: [
            "-DCMAKE_DSYMUTIL=\${WORKSPACE}/host-compiler/bin/dsymutil"
        ]
    ],
    testConfig: [
        test_type: "testlong",
        timeout: 420
    ]
])