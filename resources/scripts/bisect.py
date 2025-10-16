#!/usr/bin/env python3
import json
import subprocess
import sys
import math
import argparse
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any, Optional


class BisectionManager:
    def __init__(self, repo_path: str = "."):
        self.repo_path = Path(repo_path)

    def initialize_bisection(self, good_commit: str, bad_commit: str,
                             state_file: str) -> Dict[str, Any]:
        """Initialize a new bisection session"""

        # Get commit range
        commits = self._get_commit_range(good_commit, bad_commit)
        estimated_steps = math.ceil(math.log2(len(commits))) if len(commits) > 1 else 0

        state = {
            "session_id": f"bisect_{int(datetime.now().timestamp())}",
            "status": "initialized",
            "good_commit": good_commit,
            "bad_commit": bad_commit,
            "current_good": good_commit,
            "current_bad": bad_commit,
            "commits_to_test": commits[1:-1],  # Exclude the boundary commits
            "estimated_steps": estimated_steps,
            "completed_steps": 0,
            "test_results": {},
            "start_time": datetime.now().isoformat(),
            "last_updated": datetime.now().isoformat(),
            "metadata": {
                "total_commits": len(commits),
                "repo_path": str(self.repo_path)
            },
            "next_action": None
        }

        # Determine first commit to test
        self._update_next_action(state)

        self._save_state(state, state_file)

        print(f"Bisection initialized: {len(commits)} commits in range, "
              f"estimated {estimated_steps} steps")

        return state

    def record_test_result(self, commit: str, result: str, state_file: str) -> Dict[str, Any]:
        """Record the result of testing a commit and update bisection state"""

        state = self._load_state(state_file)

        if not state.get("next_action") or state["next_action"].get("commit") != commit:
            raise ValueError(f"Unexpected commit {commit}. Expected {state.get('next_action', {}).get('commit')}")

        # Normalize result to boolean (True = good, False = bad)
        is_good = result.upper() in ["SUCCESS", "PASSED", "STABLE", "GOOD", "TRUE"]

        # Record the result
        state["test_results"][commit] = {
            "result": result,
            "is_good": is_good,
            "timestamp": datetime.now().isoformat(),
            "step_number": state["completed_steps"] + 1
        }

        state["completed_steps"] += 1
        state["last_updated"] = datetime.now().isoformat()

        # Update bisection boundaries based on result
        if is_good:
            # This commit is good, so the failure is in later commits
            state["current_good"] = commit
            print(f"Commit {commit} is GOOD - first failure is in later commits")
        else:
            # This commit is bad, so the failure is in earlier commits
            state["current_bad"] = commit
            print(f"Commit {commit} is BAD - first failure is in earlier commits")

        # Update what to test next
        self._update_next_action(state)

        self._save_state(state, state_file)

        return state

    def get_current_state(self, state_file: str) -> Dict[str, Any]:
        """Get the current bisection state"""
        return self._load_state(state_file)

    def _update_next_action(self, state: Dict[str, Any]) -> None:
        """Update the next_action field based on current bisection state"""

        # Get commits between current good and bad
        commits_in_range = self._get_commit_range(
            state["current_good"],
            state["current_bad"]
        )

        # Remove boundary commits and already tested commits
        commits_to_test = []
        for commit in commits_in_range[1:-1]:  # Exclude boundaries
            if commit not in state["test_results"]:
                commits_to_test.append(commit)

        if len(commits_to_test) == 0:
            # Bisection is complete
            state["status"] = "complete"
            state["next_action"] = {
                "type": "complete",
                "failing_commit": state["current_bad"],
                "message": f"Bisection complete. First bad commit: {state['current_bad']}"
            }
            print(f"Bisection complete! First bad commit: {state['current_bad']}")
            return

        # Find the midpoint commit to test next
        midpoint_index = len(commits_to_test) // 2
        next_commit = commits_to_test[midpoint_index]

        # Calculate progress information
        remaining_commits = len(commits_to_test)
        remaining_steps = math.ceil(math.log2(remaining_commits)) if remaining_commits > 1 else 1
        progress_percentage = (state["completed_steps"] / state["estimated_steps"] * 100) if state["estimated_steps"] > 0 else 0

        state["status"] = "testing"
        state["next_action"] = {
            "type": "test_commit",
            "commit": next_commit,
            "commit_info": self._get_commit_info(next_commit),
            "progress": {
                "remaining_commits": remaining_commits,
                "remaining_steps": remaining_steps,
                "completed_steps": state["completed_steps"],
                "total_steps": state["estimated_steps"],
                "percentage": round(progress_percentage, 1)
            },
            "bisection_range": {
                "current_good": state["current_good"],
                "current_bad": state["current_bad"],
                "commits_in_range": len(commits_in_range)
            }
        }

        print(f"Next commit to test: {next_commit}")
        print(f"Progress: {remaining_commits} commits remaining, "
              f"~{remaining_steps} steps left ({progress_percentage:.1f}% complete)")

    def _get_commit_range(self, good_commit: str, bad_commit: str) -> List[str]:
        """Get the list of commits between good and bad (inclusive)"""
        try:
            # Get commits from good (exclusive) to bad (inclusive)
            result = subprocess.run([
                "git", "rev-list", "--reverse", f"{good_commit}..{bad_commit}"
            ], cwd=self.repo_path, capture_output=True, text=True, check=True)

            commits = [line.strip() for line in result.stdout.strip().split('\n') if line.strip()]

            # Include the boundary commits
            return [good_commit] + commits + [bad_commit]

        except subprocess.CalledProcessError as e:
            raise RuntimeError(f"Failed to get commit range {good_commit}..{bad_commit}: {e}")

    def _get_commit_info(self, commit: str) -> Dict[str, str]:
        """Get basic information about a commit"""
        try:
            # Get commit info in format: author|date|subject
            result = subprocess.run([
                "git", "show", "-s", "--format=%an|%ad|%s", commit
            ], cwd=self.repo_path, capture_output=True, text=True, check=True)

            parts = result.stdout.strip().split('|', 2)
            return {
                "author": parts[0] if len(parts) > 0 else "Unknown",
                "date": parts[1] if len(parts) > 1 else "Unknown",
                "subject": parts[2] if len(parts) > 2 else "Unknown"
            }
        except subprocess.CalledProcessError:
            return {
                "author": "Unknown",
                "date": "Unknown",
                "subject": "Unknown"
            }

    def _save_state(self, state: Dict[str, Any], state_file: str) -> None:
        """Save bisection state to file"""
        with open(state_file, 'w') as f:
            json.dump(state, f, indent=2)

    def _load_state(self, state_file: str) -> Dict[str, Any]:
        """Load bisection state from file"""
        try:
            with open(state_file, 'r') as f:
                return json.load(f)
        except FileNotFoundError:
            raise FileNotFoundError(f"State file not found: {state_file}")
        except json.JSONDecodeError as e:
            raise ValueError(f"Invalid JSON in state file {state_file}: {e}")


def main():
    """Command line interface"""
    parser = argparse.ArgumentParser(description='Git Bisection Manager - Pure git bisection logic')
    subparsers = parser.add_subparsers(dest='command', help='Available commands')

    # Initialize command
    init_parser = subparsers.add_parser('init', help='Initialize a new bisection')
    init_parser.add_argument('good_commit', help='Known good commit SHA')
    init_parser.add_argument('bad_commit', help='Known bad commit SHA')
    init_parser.add_argument('state_file', help='Path to state file to create/update')
    init_parser.add_argument('--repo-path', default='.',
                             help='Path to git repository (default: current directory)')

    # Record result command
    record_parser = subparsers.add_parser('record', help='Record test result for a commit')
    record_parser.add_argument('commit', help='Commit SHA that was tested')
    record_parser.add_argument('result', help='Test result (SUCCESS/FAILURE/GOOD/BAD/etc)')
    record_parser.add_argument('state_file', help='Path to state file to update')
    record_parser.add_argument('--repo-path', default='.',
                               help='Path to git repository (default: current directory)')

    # Status command
    status_parser = subparsers.add_parser('status', help='Get current bisection status')
    status_parser.add_argument('state_file', help='Path to state file to read')
    status_parser.add_argument('--repo-path', default='.',
                               help='Path to git repository (default: current directory)')

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    try:
        manager = BisectionManager(args.repo_path)

        if args.command == 'init':
            state = manager.initialize_bisection(
                args.good_commit,
                args.bad_commit,
                args.state_file
            )
            print(json.dumps(state, indent=2))

        elif args.command == 'record':
            state = manager.record_test_result(
                args.commit,
                args.result,
                args.state_file
            )
            print(json.dumps(state, indent=2))

        elif args.command == 'status':
            state = manager.get_current_state(args.state_file)
            print(json.dumps(state, indent=2))

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
