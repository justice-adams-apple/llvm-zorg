#!/usr/bin/env python3
"""
Artifact management for Jenkins CI builds.
Handles fetching artifacts with fallback patterns and triggering stage 1 builds.
"""

import os
import sys
import subprocess
import argparse
import logging
from pathlib import Path

# Set up logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class ArtifactManager:
    def __init__(self, workspace, s3_bucket):
        self.workspace = Path(workspace)
        self.s3_bucket = s3_bucket
        self.venv_python = self.workspace / "venv" / "bin" / "python"
        self.monorepo_build_script = self.workspace / "llvm-zorg" / "zorg" / "jenkins" / "monorepo_build.py"

    def get_git_info(self):
        """Extract git distance and SHA from the llvm-project directory."""
        try:
            llvm_project_dir = self.workspace / "llvm-project"
            os.chdir(llvm_project_dir)

            # Create first_commit tag if it doesn't exist
            subprocess.run([
                "git", "tag", "-a", "-m", "First Commit", "first_commit",
                "97724f18c79c7cc81ced24239eb5e883bf1398ef"
            ], capture_output=True)  # Ignore errors if tag exists

            # Get git description
            result = subprocess.run([
                "git", "describe", "--match", "first_commit"
            ], capture_output=True, text=True, check=True)

            git_desc = result.stdout.strip()
            parts = git_desc.split("-")

            git_distance = parts[1]
            git_sha = parts[2][1:]  # Remove 'g' prefix

            os.chdir(self.workspace)

            logger.info(f"Git info: distance={git_distance}, sha={git_sha}")
            return git_distance, git_sha

        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to get git info: {e}")
            raise
        except Exception as e:
            logger.error(f"Unexpected error getting git info: {e}")
            raise

    def construct_artifact_names(self, job_name, git_distance, git_sha):
        """Construct primary and templated artifact names."""
        base_name = f"clang-d{git_distance}-g{git_sha}.tar.gz"

        primary_artifact = f"{job_name}/{base_name}"
        # ToDo: This -templated suffix will likely change
        templated_artifact = f"{job_name}-templated/{base_name}"

        return primary_artifact, templated_artifact

    def fetch_artifact(self, artifact_name):
        """Attempt to fetch a specific artifact."""
        try:
            logger.info(f"Attempting to fetch artifact: {artifact_name}")

            env = os.environ.copy()
            env["ARTIFACT"] = artifact_name

            result = subprocess.run([
                str(self.venv_python),
                str(self.monorepo_build_script),
                "fetch"
            ], env=env, capture_output=True, text=True)

            if result.returncode == 0:
                logger.info(f"Successfully fetched artifact: {artifact_name}")
                return True
            else:
                logger.warning(f"Failed to fetch artifact {artifact_name}: {result.stderr}")
                return False

        except Exception as e:
            logger.error(f"Error fetching artifact {artifact_name}: {e}")
            return False

    def check_host_compiler(self):
        """Check if host-compiler directory exists and list clang versions."""
        host_compiler_dir = self.workspace / "host-compiler" / "lib" / "clang"

        if host_compiler_dir.exists():
            try:
                versions = list(host_compiler_dir.iterdir())
                if versions:
                    version = versions[0].name
                    logger.info(f"Host compiler version: {version}")
                    return version
                else:
                    logger.warning("No clang versions found in host-compiler")
                    return None
            except Exception as e:
                logger.error(f"Error checking host compiler: {e}")
                return None
        else:
            logger.warning("host-compiler directory not found")
            return None

    def fetch_with_fallback(self, job_name, provided_artifact=None):
        """
        Main method to fetch artifacts with fallback logic.

        Args:
            job_name: Jenkins job name
            provided_artifact: Explicit artifact name (for non-bisection jobs)

        Returns:
            tuple: (success, used_artifact, needs_stage1)
        """
        # If explicit artifact is provided, use it directly
        if provided_artifact:
            logger.info(f"Using provided artifact: {provided_artifact}")
            success = self.fetch_artifact(provided_artifact)
            if success:
                version = self.check_host_compiler()
                return True, provided_artifact, False
            else:
                logger.info("Provided artifact not found, stage 1 build needed")
                return False, provided_artifact, True

        # For bisection jobs or when no artifact provided, use fallback logic
        git_distance, git_sha = self.get_git_info()
        primary_artifact, templated_artifact = self.construct_artifact_names(
            job_name, git_distance, git_sha
        )

        # Try primary artifact first
        if self.fetch_artifact(primary_artifact):
            version = self.check_host_compiler()
            return True, primary_artifact, False

        # Try templated artifact as fallback
        logger.info("Primary artifact not found, trying templated artifact...")
        if self.fetch_artifact(templated_artifact):
            version = self.check_host_compiler()
            return True, templated_artifact, False

        # Neither found, need stage 1 build
        logger.info("No artifacts found, stage 1 build needed")
        return False, templated_artifact, True


def main():
    parser = argparse.ArgumentParser(description='Manage Jenkins build artifacts')
    parser.add_argument('--job-name', required=True, help='Jenkins job name')
    parser.add_argument('--workspace', default=os.getcwd(), help='Jenkins workspace directory')
    parser.add_argument('--s3-bucket', help='S3 bucket name (from environment if not provided)')
    parser.add_argument('--artifact', help='Specific artifact to fetch (for non-bisection jobs)')
    parser.add_argument('--output-file', default='artifact_result.properties',
                        help='File to write results to')

    args = parser.parse_args()

    # Get S3 bucket from environment if not provided
    s3_bucket = args.s3_bucket or os.environ.get('S3_BUCKET')
    if not s3_bucket:
        logger.error("S3_BUCKET must be provided via --s3-bucket or S3_BUCKET environment variable")
        sys.exit(1)

    try:
        manager = ArtifactManager(args.workspace, s3_bucket)
        success, used_artifact, needs_stage1 = manager.fetch_with_fallback(
            args.job_name, args.artifact
        )

        # Write results to properties file for Jenkins to read
        with open(args.output_file, 'w') as f:
            f.write(f"ARTIFACT_FOUND={str(success).lower()}\n")
            f.write(f"USED_ARTIFACT={used_artifact}\n")
            f.write(f"NEEDS_STAGE1={str(needs_stage1).lower()}\n")

        logger.info(f"Results written to {args.output_file}")

        # Exit with appropriate code
        sys.exit(0 if success else 1)

    except Exception as e:
        logger.error(f"Fatal error: {e}")
        sys.exit(2)


if __name__ == "__main__":
    main()
