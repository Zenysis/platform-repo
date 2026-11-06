#!/usr/bin/env groovy
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import groovy.transform.Field

@Field def DETAILED_STAGE_STATUS = [:]

// Explicitly use a method since otherwise credentials won't parse params correctly
def isStagingPipeline(Map config) {
  return params.STAGING_PIPELINE
}

def sanitizeBranchNameForDockerTag(String branch) {
  // Keep in sync with .github/scripts/utils.sh.
  return branch.replace('/', '__').replaceAll(/[^a-zA-Z0-9._-]/, '-')
}

def call(Map config) {
  def getConfig = { String configKey, String configDefault = null ->
    return config."$configKey" ?: "$configDefault"
  }
  pipeline {
    agent any
    parameters {
      booleanParam(name: 'RUN_GENERATE',
                   defaultValue: (config.RUN_GENERATE_DEFAULT == null) ? true : config.RUN_GENERATE_DEFAULT,
                   description: 'Run the generate pipeline')
      booleanParam(name: 'RUN_PROCESS',
                   defaultValue: (config.RUN_PROCESS_DEFAULT == null) ? true : config.RUN_PROCESS_DEFAULT,
                   description: 'Run the process pipeline')
      booleanParam(name: 'RUN_INDEX',
                   defaultValue: (config.RUN_INDEX_DEFAULT == null) ? true : config.RUN_INDEX_DEFAULT,
                   description: 'Run the index pipeline')
      booleanParam(name: 'RUN_VALIDATE',
                   defaultValue: (config.RUN_VALIDATE_DEFAULT == null) ? true : config.RUN_VALIDATE_DEFAULT,
                   description: 'Run the validate pipeline')
      string(name: 'PIPELINE_BRANCH',
             defaultValue: config.PIPELINE_BRANCH_DEFAULT ?: 'master',
             description: 'Branch to run a staging pipeline from (REQUIRES CHECKING BELOW TOO). Make sure you\'ve built the image first: https://github.com/Zenysis/zenysis/actions/workflows/aws-image-build-pipeline.yml')
      booleanParam(name: 'STAGING_PIPELINE',
                   defaultValue: false,
                   description: 'Run a staging pipeline')
      booleanParam(name: 'STAGING_COPY_OBJECT_STORAGE',
                   defaultValue: true,
                   description: 'If a staging pipeline, copy object storage from prod -> staging first')
    }
    environment {
      // Need to add the source root to the PYTHONPATH so our absolute imports
      // work from any directory
      PYTHONPATH = "${WORKSPACE}"

      // Disable the virtualenv PS1 prompt since our use of bash strict
      // mode causes the scripts to fail because PS1 isn't set.
      VIRTUAL_ENV_DISABLE_PROMPT = 1

      ZEN_ENV = getConfig('ZEN_ENV')
      PIPELINE_DEPLOYMENT_NAME = getConfig('PIPELINE_DEPLOYMENT_NAME')
      SLACK_CHANNEL = getConfig('SLACK_CHANNEL')
      START_DATE = "${new Date().format("yyyyMMdd", TimeZone.getTimeZone('UTC'))}"
      PIPELINE_CRONTAB = getConfig('CRON')
      PIPELINE_START_TS = new Date().format("yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone('UTC'))
      MIN_DISK_SPACE_PERCENT = getConfig('MIN_DISK_SPACE_PERCENT', 5) // default is 5% of total disk space
      PIPELINE_FAILURES_CHANNEL = "#pipeline-failures"
      CURRENT_STAGE = 'setup'

      // Determine whether we're running a staging pipeline or a prod pipeline
      PIPELINE_MODE = "${isStagingPipeline(config) ? 'staging' : 'all'}"
    }
    options {
      timeout(time: config.TIMEOUT?:6, unit: 'HOURS')
    }
    triggers {
      cron(config.CRON ?: '')
    }
    stages {
      stage('Initialize') {
        steps {
          sh 'echo "The workspace directory is: $WORKSPACE"'

          script {
            def envParams = ['PIPELINE_DEPLOYMENT_NAME', 'ZEN_ENV', 'SLACK_CHANNEL', 'MACHINE']
            for (envParam in envParams) {
              if (config."$envParam" == '') {
                error("You must specify parameter '$envParam'")
              }
            }
          }

          checkout scm
        }
      }

      stage('Install Dependencies') {
        steps {
          sh '''\
#!/bin/bash -eu
set -ox pipefail

# The shebang in virtualenv generated scripts might be too long for the
# system to properly process it. Swap the absolute path with an env lookup.
# This needs to happen after pip installing since some requirements might add
# new binaries.
FixShebangs () {
  local capture_pattern;
  capture_pattern=\'^#!"?\'"${WORKSPACE}"\'/\\.venv_dev/bin/(.+?)"?$\'

  # Replace all instances with an env lookup
  perl -pi -e "s:${capture_pattern}:#!/usr/bin/env \\$1:" .venv_dev/bin/*
}

# If the virtualenv uses the wrong python version, remove it.
if [ -d ".venv_dev" ] && [[ $(.venv_dev/bin/python --version) != *3.11* ]] ; then
  echo "Removing virtualenv using incorrect python version"
  rm -rf .venv_dev
fi
# If no virtualenv has been set up, initialize a new one.
if [ ! -d ".venv_dev" ]  || [ ! -d ".venv_uv" ] ; then
  echo "Creating new virtualenv"
  # We use a separate venv just to install `uv` without affecting
  # global state of the OS. It later is used to set up the main
  # venv with required python version and all dependencies
  python3 -m venv .venv_uv
  source .venv_uv/bin/activate
  # Install uv for Python dependency management
  pip install uv
  uv venv --clear .venv_dev --python 3.11
  FixShebangs
fi

source .venv_uv/bin/activate
uv --version

# Need to fix the paths again in case pip was upgraded.
FixShebangs

# Only need fab requirements
# Export via uv otherwise it wants to install via the AWS package index by default
uv export \
  --only-group helper-fab \
  --locked \
  --format requirements.txt | \
uv --no-config pip install \
   --python .venv_dev \
   -r /dev/stdin

# Fix shebangs one more time since pip might install new binaries with the wrong
# long shebang path.
FixShebangs
'''
        }
        post {
          failure {
            script {
              storeStatus(
                config, 'Install Dependencies', FAILURE,
                ['Unknown issue installing dependencies'], null)
            }
          }
        }
      }

      stage('Staging setup') {
        // This always executes when other steps run because we either need to 1) setup a staging
        // pipeline or 2) check whether we're running a "prod" or normal pipeline
        when {
          expression {
            params.RUN_GENERATE || params.RUN_PROCESS || params.RUN_INDEX || params.RUN_VALIDATE || params.STAGING_PIPELINE
          }
        }
        steps {
          script {
            CURRENT_STAGE = 'staging setup'
            runStagingPipelineSetup(config, params.PIPELINE_BRANCH)
          }
        }
      }

      stage('Generate') {
        when {
          expression {
            params.RUN_GENERATE
          }
        }
        steps {
          script {
            CURRENT_STAGE = 'generate'
            checkFreeDiskSpace(config)
            runDockerPipelineCommand(
              config, 'generate', params.PIPELINE_BRANCH,
              config.GENERATE_STEPS ?: 'run/...', true)
          }
        }
        post {
          // Capture timeout events and allow the pipeline to continue. The
          // `aborted` event will only happen if this stage times out or if
          // the Jenkins pipeline is manually stopped.
          aborted {
            script {
              currentBuild.result = 'UNSTABLE'
            }
          }

          // Since the `generate` pipeline command is set to allow failures, we
          // will only see a failure case if there is an issue connecting to the
          // MoH machine. In those cases, we want the pipeline to continue
          // running, since the MoH network is not stable.
          failure {
            script {
              currentBuild.result = 'SUCCESS'
            }
          }
        }
      }

      stage('Process') {
        when {
          expression {
            params.RUN_PROCESS
          }
        }
        steps {
          script {
            CURRENT_STAGE = 'process'
            checkFreeDiskSpace(config)
            runDockerPipelineCommand(config, 'process', params.PIPELINE_BRANCH)
          }
        }
      }

      stage('Index') {
        when {
          expression {
            params.RUN_INDEX
          }
        }
        steps {
          script {
            CURRENT_STAGE = 'index'
            checkFreeDiskSpace(config)
            runDockerPipelineCommand(config, 'index', params.PIPELINE_BRANCH)
          }
        }
      }

      // TODO(stephen): Should these sections only run if an indexing step ran?
      stage('Validate') {
        when {
          expression {
            params.RUN_VALIDATE
          }
        }
        steps {
          script {
            CURRENT_STAGE = 'validate'
            checkFreeDiskSpace(config)
            runDockerPipelineCommand(config, 'validate', params.PIPELINE_BRANCH, null, true)
          }
        }
      }
    }

    post {
      always {
        script {
          if (DOCKER_PROJECT_NAME) {
            // We're not expecting any containers to still exist, so output the logs for debugging purposes
            echo "Logs (before the abort):\n${getDockerLogs(config, DOCKER_PROJECT_NAME)}"
          }
        }

        stopAndRemoveContainers(config)
        cleanupDockerImages(config)
      }
      aborted {
        storeStatus(config, CURRENT_STAGE, FAILURE, ['Pipeline timed out or was otherwise aborted. Check datadog logs for information about the pipeline stage that timed out.'], null)
      }
      unsuccessful {
        script {
          // UNSTABLE means some steps failed but every stage still completed,
          // so the run should not be recorded as failed.
          if (currentBuild.currentResult != 'UNSTABLE') {
            // Only log for setup failures since the other stages will have already logged
            if (CURRENT_STAGE == 'setup') {
              storeStatus(config, CURRENT_STAGE, FAILURE, ['Unknown failure during setup'], null)
            }
            storeFailureStatus(config, params.PIPELINE_BRANCH)
          }
        }
      }
      cleanup {
        // Run this in cleanup to collect logs from other post stages
        notifyBuild(config)
      }
    }
  }
}

// Run a docker specific fabric command and capture output
def getOutputFromFabricCommand(String fabCommand, String machine) {
  def machineArg = machine.contains('@') ? "-H '${machine}'" : "-R '${machine}'"
  def storageStatus = sh(script: """\
#!/bin/bash -eu
set -o pipefail

source .venv_dev/bin/activate
fab --fabfile=prod/docker_fabfile.py ${machineArg} '${fabCommand}'
""", returnStdout: true).trim()

  return storageStatus
}

// get host from ZEN_ENV
def getHostFromMachine(String machine) {
  if (machine.contains('@')) {
    return machine
  }

  def hostResult = sh(script: """\
#!/bin/bash -eu
set -o pipefail

source .venv_dev/bin/activate
./prod/dockerpipeline/get_host_from_machine.py -m '${machine}'
""", returnStdout: true).trim()
  if (hostResult == '') {
    error("Could not find host for machine '${machine}'")
  }

  return hostResult
}


def getZenBaseDir() {
  def zenBaseDir = sh(script: """\
#!/bin/bash -eu
set -o pipefail

source .venv_dev/bin/activate
./prod/dockerpipeline/get_zen_base_dir.py
""", returnStdout: true).trim()
  if (zenBaseDir == '') {
    error("Could not find ZEN_HOME on machine")
  }

  return zenBaseDir
}

// Get the override file for a machine
// This override allows us to run as root user on some deployments
def getOverrideFile(String deployment) {
  def overrideFile = sh(script: """\
#!/bin/bash -eu
set -o pipefail

source .venv_dev/bin/activate
./prod/dockerpipeline/get_docker_override_file.py -d '${deployment}'
""", returnStdout: true).trim()

  // The override file could be empty based on a deployment. This is by design.
  return overrideFile
}


// Constants for Zeus parsing.
@Field def CONFIG_START = 'CONFIG:\n'
@Field def FAILURE_START = 'FAILURE: tasks: [\n'
@Field def ZEUS_RUN = 'run'
@Field def ZEUS_EXPORT = 'export'
@Field def ZEUS_PUBLISH = 'publish'
@Field def ZEUS_STEPS = ['run', 'export', 'publish']
@Field def ZEUS_STEPS_REVERSE = ['publish', 'export', 'run']
// Constants for slack messages.
@Field def SUCCESS = 'good'
@Field def PARTIAL_FAILURE = 'warning'
@Field def FAILURE = 'danger'
// Constant for docker project name
@Field def DOCKER_PROJECT_NAME = null
@Field def INSUFFICIENT_STORAGE = 'insufficient_storage'

def getDateEnv() {
  def buildNumber = env.BUILD_NUMBER.toInteger()
  if (buildNumber > 99999) {
    error("Build numbers higher than 99999 are not supported")
  }
  return "${env.START_DATE}-${String.format('%05d', buildNumber)}"
}

def getDockerLogs(Map config, String dockerProjectName) {
  def overrideFile = getOverrideFile(env.ZEN_ENV)
  def dockerHost = getHostFromMachine(config.MACHINE)

  return sh(
    script: "DOCKER_API_VERSION=\"1.44\" DATABASE_URL=\"\" DRUID_HOST=\"\" PIPELINE_ENV=\"${env.PIPELINE_DEPLOYMENT_NAME}\" DOCKER_HOST=\"ssh://${dockerHost}\" docker compose -p \"${dockerProjectName}-etl\" -f docker-compose.pipeline.yml ${overrideFile} logs --no-log-prefix",
    returnStdout: true
  ).trim()
}

// Run a docker command on a remote machine and wait for it to complete.
// Since it detaches from the pipeline process and runs on its own, we want to
// send a webhook url and wait for the detached process to notify us when the
// command is complete.
def runDockerCommand(Map config, String commandPath, String command,
                     String jobName, String branch) {
  // Generate a webhook that results should be passed to

  def now = new Date()
  def nowFormatted = now.format("yyMMddHHmmssSSS", TimeZone.getTimeZone('UTC'))
  def safeJobName = jobName.replaceAll(' ', '_')
  def stagingPrefix = PIPELINE_MODE == 'staging' ? 'staging-' : ''
  def dockerProjectName = "${stagingPrefix}pipeline-${env.PIPELINE_DEPLOYMENT_NAME}-${safeJobName}-${nowFormatted}"

  def localBaseDir = getZenBaseDir()
  def outputPath = "${localBaseDir}/pipeline/out"
  // Differs from PIPELINE_MODE because this is only ever stag or prod and is used to set up the correct creds
  def pipelineType = isStagingPipeline(config) ? 'stag' : 'prod'
  def overrideFile = getOverrideFile(ZEN_ENV)

  def psResult = null

  // print outputPath
  echo "Pipeline output path: ${outputPath}"

  // set the docker project name for the command
  DOCKER_PROJECT_NAME = dockerProjectName

  // Get the host from config.MACHINE
  def dockerHost = getHostFromMachine(config.MACHINE)

  echo "Running docker command on host: ${dockerHost}"

  try {
    // Run the docker command
    def dockerTag = sanitizeBranchNameForDockerTag(branch)

    sh """\
      DOCKER_API_VERSION="1.44" \
      DATABASE_URL="" \
      DRUID_HOST="" \
      DOCKER_HOST="ssh://${dockerHost}" \
      DOCKER_TAG="${dockerTag}" \
      ZEN_ENV="${env.ZEN_ENV}" \
      PIPELINE_ENV="${env.PIPELINE_DEPLOYMENT_NAME}" \
      COMMAND_PATH="${commandPath}" \
      COMMAND="${command}" \
      OUTPUT_PATH="${outputPath}" \
      DATE="${getDateEnv()}" \
      PIPELINE_START_TS="${env.PIPELINE_START_TS}" \
      PIPELINE_CRONTAB="${env.PIPELINE_CRONTAB}" \
      JOB_NAME="${env.JOB_NAME}" \
      PIPELINE_MODE="${PIPELINE_MODE}" \
      PIPELINE_TYPE="${pipelineType}" \
      docker compose -p "${dockerProjectName}-etl" \
        -f docker-compose.pipeline.yml \
        ${overrideFile} \
        up -d
    """

  } catch (Exception e) {
    storeStatus(config, jobName, FAILURE, ["Failure while executing docker command: ${e.toString()}"], null)

    throw e
  }

  try {
    echo "Waiting for command to finish."

    waitUntil(initialRecurrencePeriod: 15000) {
      // Set DATABASE_URL and DRUID_HOST to empty strings to avoid docker warnings.
      def result = sh(script: "DOCKER_API_VERSION=\"1.44\" DATABASE_URL=\"\" DRUID_HOST=\"\" PIPELINE_ENV=\"${env.PIPELINE_DEPLOYMENT_NAME}\" DOCKER_HOST=\"ssh://${dockerHost}\" docker compose -p \"${dockerProjectName}-etl\" -f docker-compose.pipeline.yml ${overrideFile} ps -a --format json", returnStdout: true).trim()
      if (result == '') {
        throw new Exception('Could not get docker ps result')
      }
      psResult = new JsonSlurperClassic().parseText(result)
      // Possible states are: [paused | restarting | removing | running | dead | created | exited]
      // https://docs.docker.com/reference/cli/docker/container/ls/#status
      // The 'dead' status indicate that the container is not running, but doesn't have an exit code we can use.
      if (psResult.State == 'dead') {
        throw new Exception('Docker container is in `dead` state')
      }
      // We are only happy with the 'exited' state, indicating the command has completed.
      if (psResult.State == 'exited') {
        return true
      }
      // This makes the logs a lot more chatty, but it's useful for debugging the pipeline state.
      echo "Docker container state: ${psResult.State} with exit code: ${psResult.ExitCode}"
      // waitUntil sleep for a maximum of 15 seconds, we might as well sleep longer to reduce
      // all our logging.
      sleep(60)
      return false
    }
  } catch (Exception e) {
    echo e.toString()
    storeStatus(config, jobName, FAILURE, ["Failure while waiting for docker to complete: ${e.toString()}"], null)
    throw e
  }
  echo "Docker container state: ${psResult.State} with exit code: ${psResult.ExitCode}"
  echo "Grabbing logs."

  def logs = getDockerLogs(config, dockerProjectName)
  def data = [logs: logs, exit_code: psResult.ExitCode as Integer]
  // Stop containers in case no exception was thrown
  stopAndRemoveContainers(config)

  // Unset the docker project name so we don't try to stop containers again
  DOCKER_PROJECT_NAME = null

  return data
}

// Run a zeus pipeline command on a remote machine and wait for it to complete.
// TODO(stephen): Probably should handle timeouts
// TODO(stephen): When Jenkins adds support for failing tasks to not stop
// the pipeline, remove the allowPipelineFailure option and use that behavior.
def runDockerPipelineCommand(Map config, String pipelineStage, String branch,
                             String steps = null, boolean allowPipelineFailure = false) {
  // Create the zeus command
  def zeusCommand = "./${pipelineStage}_all"
  if (pipelineStage == "generate") {
    // Certain pipeline stages require the list of steps to run to be specified.
    if (steps) {
      zeusCommand = "./generate_wrapper ${steps}"
    }
    else {
      zeusCommand = "./generate_wrapper"
    }
  }

  def pipelineCommandPath = "pipeline/${env.PIPELINE_DEPLOYMENT_NAME}/${pipelineStage}"

  def data = runDockerCommand(config, pipelineCommandPath, zeusCommand, pipelineStage, branch)
  def logs = data.logs.trim()
  echo "Result:\n${logs}"

  def pipeline_date = parseDateFromZeusOutput(logs)
  def (status, failures) = parseZeusOutput(logs, allowPipelineFailure)

  storeStatus(config, pipelineStage, status, failures, pipeline_date)
  if (status == FAILURE) {
    error('Pipeline Failed')
  } else if (status == PARTIAL_FAILURE) {
    // Matches the yellow Slack attachment.
    unstable("${pipelineStage.capitalize()} partially failed")
  }
}

// Checks the free space before running the pipeline
// If the free space is less than MIN_DISK_SPACE_PERCENT (default: 5% of total storage)
// of the total disk space, abort the pipeline
def checkFreeDiskSpace(Map config) {
    command = "get_free_disk_space:$env.MIN_DISK_SPACE_PERCENT"

    def freeSpaceResult = getOutputFromFabricCommand(command, config.MACHINE)
    if (freeSpaceResult.contains(INSUFFICIENT_STORAGE)) {
        currentBuild.result = 'FAILURE'
        storeStatus(config, "Free Disk Space", FAILURE, ["Insufficient disk space left. Aborting pipeline run."], null)
        error("Insufficient disk space. Aborting pipeline run.")
    }
}

// Function to stop and remove all docker containers
def stopAndRemoveContainers(Map config) {
  if (!DOCKER_PROJECT_NAME) {
    return
  }

  def dockerHost = getHostFromMachine(config.MACHINE)

  echo "Cleaning up docker containers for project name: ${DOCKER_PROJECT_NAME} on host ${dockerHost}"

  try {
    sh """\
      DOCKER_API_VERSION="1.44" \
      DOCKER_HOST="ssh://${dockerHost}" \
      docker compose -p "${DOCKER_PROJECT_NAME}-etl" down
    """
  } catch (Exception e) {
    def errorMessage = "Failure while attemtping to stop/remove docker containers for project ${DOCKER_PROJECT_NAME} on host ${dockerHost}"
    storeStatus(config, '**Docker Compose Down**', FAILURE, [errorMessage], null)
    throw e
  }
}

// Docker cleanup func
def cleanupDockerImages(Map config) {
  def dockerHost = getHostFromMachine(config.MACHINE)

  echo "Cleaning up dangling and unused docker images on host ${dockerHost}"

  try {
    sh """\
      DOCKER_HOST="ssh://${dockerHost}" \
      docker image prune --force --filter until=24h
    """
  } catch (Exception e) {
    def errorMessage = "Failure while attemtping to cleanup dangling and unused docker images on host ${dockerHost}"
    storeStatus(config, '**Docker Cleanup**', FAILURE, [errorMessage], null)
    throw e
  }
}

// This function will parse the pipeline date from the zeus output message.
def parseDateFromZeusOutput(String message) {
    def matcher = message =~ /"PIPELINE_DATE"\s*:\s*"([^"]+)"/
    matcher.find()
    return matcher.group(1)
}

// Parse the Zeus output message returned to Jenkins and determine the status
// and failure messages to show.
def parseZeusOutput(String message, boolean allowPipelineFailure) {
  def stageResults = parseZeusStages(message)
  def failures = []
  if (!stageResults) {
    return [FAILURE, ['*** Unknown Failure ***']]
  }

  // Determine which zeus stages failed and at what severity.
  if (stageResults[ZEUS_RUN]) {
    // Always add the `run` failures since they are the most useful.
    failures.addAll(stageResults[ZEUS_RUN])
  } else if (stageResults[ZEUS_EXPORT]) {
    // The export step failing is interesting, but the failure messages are only
    // interesting if the run step passed.
    for (failedStep in stageResults[ZEUS_EXPORT]) {
      failures.add("${failedStep} (export)")
    }
  }

  // The status is SUCCESS if all stages that ran had no failures (since export
  // and publish are not always guaranteed to run).
  def status = failures ? FAILURE : SUCCESS
  for (step in ZEUS_STEPS_REVERSE) {
    def stepFailures = stageResults[step]
    // If a step ran, it will have a non-null failures list.
    if (stepFailures != null) {
      // Change the status to PARTIAL_FAILURE if there is a zeus step that ran
      // successfully after a failed earlier step (like if `run` had failures
      // but did not abort, `export` and `publish` will still run successfully.
      // In those cases, we want a partial failure to be reported not a full
      // failure).
      if (!stepFailures && status == FAILURE) {
        status = PARTIAL_FAILURE
      } else if (stepFailures && allowPipelineFailure && status == FAILURE && !stepFailures.any { it.contains('.abort_fail') }) {
        status = PARTIAL_FAILURE
      }
      break
    }
  }
  return [status, failures]
}

// Extract the failures from each zeus stage: run, export, and publish. If a
// stage is not run (either by design or due to a pipeline abort) it will be
// null in the output.
def parseZeusStages(String message) {
  def output = [:]
  def firstBlockIdx = message.indexOf(CONFIG_START)
  if (firstBlockIdx < 0) {
    return null
  }
  def blocks = message[firstBlockIdx + 1..-1].split(CONFIG_START)
  blocks.eachWithIndex {
    block, index ->
      output[ZEUS_STEPS[index]] = extractFailures(block)
  }

  return output
}

// Return a list of the failed pipeline steps from a zeus stage's message.
def extractFailures(String zeusStageMessage) {
  def failureStartIdx = zeusStageMessage.indexOf(FAILURE_START)
  // No failures exist.
  if (failureStartIdx < 0) {
    return []
  }

  failureStartIdx += FAILURE_START.size()

  // HACK(stephen): Awful workaround because Jenkins does not whitelist the
  // String.indexOf(String, int) Java method inside Groovy.
  def failures = zeusStageMessage[failureStartIdx..-1]
  def failureEndIdx = failures.indexOf('\n]')

  // Something went horribly wrong.
  if (failureEndIdx < 0) {
    return ['*** Unknown Failure ***']
  }

  // Extract the failed stages from the list, clean them, and return as a list.
  return failures[0..failureEndIdx]
    .replaceAll('[ ",]', '')
    .replace('//', '')
    .replace('/', '.')
    .split('\n')
}

// Extract the source of a DHIS2 validation failure from a failed step name like
// '00_dhis2_validate_source.abort_fail' -> 'source'. This is used to determine
// whether to show a link to the DHIS2 validation report in the Slack message.
@NonCPS
def extractDhis2Source(String rawStep) {
    def m = (rawStep =~ /^\d+_dhis2_validate_(.+)$/)
    return m.matches() ? m.group(1) : null
}

// This function will take a cleaned failed stage like '00_expert.10_process' and
// formats it with the URL to the log files. If enabled, it will also add a URL to
// the comparison report (pipeline steps must be set up to create these files).
def formatFailureWithURL(Map config, String failure, String pipeline_date, String stage){
    // It is most likely not a zeus failure if there is no pipeline_date
    if (pipeline_date == null){
        return failure
    }

    // Staging pipelines use a different pipeline/out location
    def deploymentName = "${env.PIPELINE_DEPLOYMENT_NAME}${PIPELINE_MODE == 'staging' ? '-stag' : ''}"
    String server_url = String.format('%s/%s', config.LOGS_SERVER, deploymentName);
    String report_url_template = '%s/%s/%s/comparison_report/';
    String source = failure.tokenize('.')[0];

    def split_source = source.split('_');
    String source_number = split_source[0];
    String source_name = source.replaceAll(String.format("%s_", source_number), "")


    String source_command = failure.tokenize('.')[1];
    source_command = source_command.split('_')[1];

    String log_url_template = '%s/logs/%s/%s/';
    // NOTE(abby): Some pipelines have stages for specific generate steps, but the link should still
    // go to the generate folder.
    if (stage.contains('Generate')) {
      stage = 'generate'
    }
    String log_url = String.format(log_url_template, server_url, stage, pipeline_date);

    String extraLinks = ''
    if (stage == 'validate') {
      String rawStep = failure.tokenize('.').size() > 1 ? failure.tokenize('.')[1] : ''
      String dhis2Source = extractDhis2Source(rawStep)
      if (dhis2Source) {
          String report_url = String.format(
              '%s/logs/validate/%s/dhis2_%s_validation_value_mismatches.html',
              server_url, pipeline_date, dhis2Source)
          String httpStatus = sh(
              returnStdout: true,
              script: "curl -s -o /dev/null -w '%{http_code}' -I -L --max-time 10 '${report_url}' || true",
          ).trim()
          if (httpStatus == '200') {
              extraLinks = ' | <' + report_url + '|View DHIS2 validation report>'
          }
      }
    }

    if (source_command == 'process' && config.INCLUDE_COMPARISON_LINKS){
        report_url = String.format(report_url_template, server_url, source_name, pipeline_date);
        failure = failure +
                    ' (<' + report_url +'comparison_report.html|View comparison report> | <'+
                    report_url +'comparison_sample.csv|Download Sample of changed data> | <'+
                    log_url + failure +'.log|View error logs>' + extraLinks + ')';
    } else {
        failure = failure + ' (<' + log_url + failure +'.log|View error logs>' + extraLinks + ')';
    }
    return failure;
}

// Store a stage's status and failures on the global detailed stage tracking
// variable.
def storeStatus(Map config, String stage, String status, List<String> rawFailures, String date) {
  def failures = []
  def hasAbortFailed = false
  for (failure in rawFailures) {
    if (hasAbortFailed) {
      failures << failure
    } else {
      failures << formatFailureWithURL(config, failure, date, stage)
      if (failure.contains('.abort_fail')) {
        failures << '*The following steps did not execute due to the above failure:*'
        hasAbortFailed = true
      }
    }
  }

  def stageName = stage.capitalize()
  def currentTime = System.currentTimeMillis() / 1000 as Integer
  if (!DETAILED_STAGE_STATUS[stageName]) {
    DETAILED_STAGE_STATUS[stageName] = [
      status: status,
      failures: failures,
      completionTime: currentTime,
    ]
    return
  }

  // Update an existing stage's status (like if two parts of the same stage are
  // run in parallel).
  def stageStatus = DETAILED_STAGE_STATUS[stageName]
  stageStatus.failures.addAll(failures)
  stageStatus.completionTime = currentTime

  // If the new status is not successful, store it for the stage. We don't want
  // to overwrite an unsuccessful status with a successful one. And if this
  // stage's status is successful, then we should preserve the existing status
  // because it is at least the same as this one.
  if (status != SUCCESS) {
    stageStatus.status = status
  }
}

// Send a detailed and pretty slack message indicating the result of this
// pipeline.
def notifyBuild(Map config) {
  // If no stages have set a status, do nothing.
  if (!DETAILED_STAGE_STATUS) {
    return
  }

  def title = "${config.TITLE ?: env.PIPELINE_DEPLOYMENT_NAME.capitalize()} Pipeline Status"
  if (PIPELINE_MODE == 'staging') {
    title = "Staging ${title}"
  } else if (PIPELINE_MODE == 'prod') {
    title = "Production ${title}"
  }
  def attachments = [[
    title: title,
    title_link: blueOceanBuildUri(),
    color: '#FFF'
  ]]

  def hasFailures = false

  for (item in DETAILED_STAGE_STATUS) {
    def attachment = [
      color: item.value.status,
      title: item.key,
      ts: item.value.completionTime,
      mrkdwn_in: 'fields',
    ]

    if (item.value.status != SUCCESS) {
      hasFailures = true
    }

    if (item.value.failures) {
      attachment.fields = [[
        value: item.value.failures.join('\n'),
      ]]
    }
    attachments.add(attachment)
  }

  // Send message to the pipeline's dedicated Slack channel
  // Tag @channel to alert about failures (tagging allows muting the channel to reduce non-failure noise)
  slackSend(
    message: hasFailures ? '@channel' : '',
    channel: env.SLACK_CHANNEL,
    attachments: JsonOutput.toJson(attachments))

  // Send duplicate to "all pipelines" channel if there were failures
  // Not tagging @channel to reduce alert fatigue and allow users to control notifications.
  // Users can unmute the channel if they want to be notified of all messages (e.g. to monitor after critical changes)
  if (hasFailures) {
    slackSend(
      message: '',
      channel: env.PIPELINE_FAILURES_CHANNEL,
      attachments: JsonOutput.toJson(attachments))
  }
}


def storeFailureStatus(Map config, String branch) {
    def commandPath = "./"
    def command = "env PIPELINE_ID=${env.ZEN_ENV}/store-failure-status python -m data.pipeline.scripts.data_digest.populate_pipeline_run_metadata --failure"
    def stage = "store failure status"

    def data = runDockerCommand(config, commandPath, command, stage, branch)
    echo "Result:\n${data.logs}"

    if (data.exit_code != 0) {
        storeStatus(config, stage, FAILURE, ['Could not store failed pipeline status. Check jenkins logs for details.'], null)
    }
}


// Programmatically build the blue ocean URI since it isn't provided in env
def blueOceanBuildUri() {
  return "http://jenkins.zenysis.com/blue/organizations/jenkins/${env.JOB_NAME}/detail/${env.JOB_BASE_NAME}/${env.BUILD_NUMBER}/pipeline/"
}

// Run the staging pipeline setup as a docker command
def runStagingPipelineSetup(Map config, String branch) {
    def commandPath = "./"
    def copy_object_storage = params.STAGING_COPY_OBJECT_STORAGE ? '--copy_object_storage' : ''
    // We want to use the minio credentials for the staging pipeline setup
    // TODO: Remove ZEN_BYPASS_VAULT_S3_CREDENTIALS once staging is using staging object storage
    // and the prod read-only credentials are in vault.
    def command = "env PIPELINE_ID=${env.ZEN_ENV}/staging-setup ZEN_BYPASS_VAULT_S3_CREDENTIALS=1 python -m data.pipeline.scripts.staging_pipeline.setup_staging_pipeline ${copy_object_storage}"
    def stage = "staging setup"

    def data = runDockerCommand(config, commandPath, command, stage, branch)

    def logs = data.logs.trim()
    echo "Result:\n${logs}"

    if (data.exit_code != 0) {
        def status = PIPELINE_MODE == 'staging' ? FAILURE : PARTIAL_FAILURE
        storeStatus(config, stage, status, ['Failure during staging setup. Check jenkins logs for details.'], null)
        // We don't want to fail the pipeline if staging is down. For a non-staging pipeline,
        // mark the build unstable, set the pipeline mode to prod and continue.
        if (PIPELINE_MODE == 'staging') {
          error('Staging setup failed')
        }
        unstable('Staging setup failed, continuing as a prod pipeline')
        PIPELINE_MODE = 'prod'
        return
    }

    // For non-staging pipeline runs, we use the above command to determine the pipeline mode
    if (PIPELINE_MODE != 'staging') {
        def new_pipeline_mode = logs.split('\n')[-1].split(' ').last()
        echo "Pipeline mode: ${new_pipeline_mode}"
        PIPELINE_MODE = new_pipeline_mode
    }
}
