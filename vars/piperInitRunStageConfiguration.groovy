import com.sap.piper.ConfigurationLoader

import static com.sap.piper.Prerequisites.checkScript

import com.sap.piper.ConfigurationHelper
import com.sap.piper.MapUtils
import groovy.transform.Field

@Field String STEP_NAME = getClass().getName()

@Field Set GENERAL_CONFIG_KEYS = [
    /**
     * Print more detailed information into the log.
     * @possibleValues `true`, `false`
     */
    'verbose',
    /**
     * The branch used as productive branch, defaults to master.
     */
    'productiveBranch',
    'globalExtensionsDirectory',
    'projectExtensionsDirectory'
]

@Field Set STEP_CONFIG_KEYS = GENERAL_CONFIG_KEYS.plus([
    /**
     * Defines the library resource that contains the stage configuration settings
     */
    'stageConfigResource'

])

@Field Set PARAMETER_KEYS = STEP_CONFIG_KEYS

void call(Map parameters = [:]) {

    def script = checkScript(this, parameters) ?: this
    def stageName = parameters.stageName?:env.STAGE_NAME

    script.commonPipelineEnvironment.configuration.runStage = [:]
    script.commonPipelineEnvironment.configuration.runStep = [:]

    // load default & individual configuration
    Map config = ConfigurationHelper.newInstance(this)
        .loadStepDefaults()
        .mixinGeneralConfig(script.commonPipelineEnvironment, GENERAL_CONFIG_KEYS)
        .mixinStepConfig(script.commonPipelineEnvironment, STEP_CONFIG_KEYS)
        .mixinStageConfig(script.commonPipelineEnvironment, stageName, STEP_CONFIG_KEYS)
        .mixin(parameters, PARAMETER_KEYS)
        .withMandatoryProperty('stageConfigResource')
        .use()


    config.stages = (readYaml(text: libraryResource(config.stageConfigResource))).stages

    //handling of stage and step activation
    config.stages.each {stage ->

        //activate stage if stage configuration is available
        if (ConfigurationLoader.stageConfiguration(script, stage.getKey())) {
            script.commonPipelineEnvironment.configuration.runStage[stage.getKey()] = true
        }
        //-------------------------------------------------------------------------------
        //detailed handling of step and stage activation based on conditions
        script.commonPipelineEnvironment.configuration.runStep[stage.getKey()] = [:]
        String currentStage = stage.getKey()
        boolean anyStepConditionTrue = false
        stage.getValue().stepConditions.each {step ->
            def stepActive = false
            step.getValue().each {condition ->
                Map stepConfig = script.commonPipelineEnvironment.getStepConfiguration(step.getKey(), currentStage)
                switch(condition.getKey()) {
                    case 'config':
                        if (checkConfig(condition, stepConfig)) {
                            stepActive = true
                        }
                        break
                    case 'configKeys':
                        if (checkConfigKeys(condition, stepConfig)) {
                            stepActive = true
                        }
                        break
                    case 'filePatternFromConfig':
                        if (checkForFilesWithPatternFromConfig(script, condition, stepConfig)) {
                            stepActive = true
                        }
                        break
                    case 'filePattern':
                        if (checkForFilesWithPattern(script, condition)) {
                            stepActive = true
                        }
                        break
                    case 'npmScripts':
                        if (checkForNpmScriptsInPackages(script, condition)) {
                            stepActive = true
                        }
                        break
                }
            }
            script.commonPipelineEnvironment.configuration.runStep."${currentStage}"."${step.getKey()}" = stepActive

            anyStepConditionTrue |= stepActive

        }
        boolean runStage = anyStepConditionTrue
        if (stage.getValue().extensionExists) {
            echo "came into if"
            runStage |= extensionExists(script as Script, config, stage.getKey())
            echo "runStage after |=: ${runStage}"
        }
        echo "Thats the value of stage: ${stage.getValue()}"
        if (stage.getValue().onlyProductiveBranch && (config.productiveBranch != env.BRANCH_NAME)) {
            runStage = false
        }
        //echo "${script.commonPipelineEnvironment.configuration}"
        echo "Thats the content of config: ${config}"
        script.commonPipelineEnvironment.configuration.runStage[currentStage] = runStage
    }

    if (config.verbose) {
        echo "[${STEP_NAME}] Debug - Run Stage Configuration: ${script.commonPipelineEnvironment.configuration.runStage}"
        echo "[${STEP_NAME}] Debug - Run Step Configuration: ${script.commonPipelineEnvironment.configuration.runStep}"
    }
}

private static boolean extensionExists(Script script, Map config, def stageName) {
    if (!stageName || !(stageName in CharSequence)) {
        script.echo "stagename not set?"
        return false
    }
    if (!script.piperStageWrapper.allowExtensions(script)) {
        script.echo "allowExtensions is false"
        return false
    }
    // NOTE: These keys exist in "config" if they are configured in the general section of the project
    // config or the defaults. However, in piperStageWrapper, these keys could also be configured for
    // the step "piperStageWrapper" to be effective. Don't know if this should be considered here for consistency.
    if (!config.globalExtensionsDirectory && !config.projectExtensionsDirectory) {
        return false
    }
    def projectInterceptorFile = "${config.projectExtensionsDirectory}${stageName}.groovy"
    def globalInterceptorFile = "${config.globalExtensionsDirectory}${stageName}.groovy"
    script.echo "thats the projectInterceptorFile: ${projectInterceptorFile}"
    script.echo "thats the globalInterceptorFile: ${globalInterceptorFile}"
    return script.fileExists(projectInterceptorFile) || script.fileExists(globalInterceptorFile)
}

private static boolean checkConfig(def condition, Map stepConfig) {
    if (condition.getValue() instanceof Map) {
        condition.getValue().each {configCondition ->
            if (MapUtils.getByPath(stepConfig, configCondition.getKey()) in configCondition.getValue()) {
                return true
            }
        }
    } else if (MapUtils.getByPath(stepConfig, condition.getValue())) {
        return true
    }
    return false
}

private static boolean checkConfigKeys(def condition, Map stepConfig) {
    if (condition.getValue() instanceof List) {
        condition.getValue().each { configKey ->
            if (MapUtils.getByPath(stepConfig, configKey)) {
                return true
            }
        }
    } else if (MapUtils.getByPath(stepConfig, condition.getValue())) {
        return true
    }
    return false
}

private static boolean checkForFilesWithPatternFromConfig (Script script, def condition, Map stepConfig) {
    def conditionValue = MapUtils.getByPath(stepConfig, condition.getValue())
    if (conditionValue && script.findFiles(glob: conditionValue)) {
        return true
    }
    return false
}

private static boolean checkForFilesWithPattern (Script script, def condition) {
    if (condition.getValue() instanceof List) {
        condition.getValue().each {configKey ->
            if (script.findFiles(glob: configKey)) {
                return true
            }
        }
    } else {
        if (script.findFiles(glob: condition.getValue())) {
            return true
        }
    }
    return false
}

private static boolean checkForNpmScriptsInPackages (Script script, def condition) {
    def packages = script.findFiles(glob: '**/package.json', excludes: '**/node_modules/**')
    for (int i = 0; i < packages.size(); i++) {
        String packageJsonPath = packages[i].path
        Map packageJson = script.readJSON file: packageJsonPath
        Map npmScripts = packageJson.scripts ?: [:]
        if (condition.getValue() instanceof List) {
            script.echo "came into list condition"
            condition.getValue().each { configKey ->
                script.echo "thats the configKey: ${configKey}"
                script.echo "thats the npmScripts: ${npmScripts}"
                //script.echo "thats the npmScripts[${configKey}]: ${npmScripts[configKey]}"
                script.echo "thats npmScripts.containsKey(${configKey}): ${npmScripts.containsKey(configKey)}"
                //script.echo "thats the npmScripts.ci-it-backend: ${npmScripts.ci-it-backend}"
                if (npmScripts.containsKey(configKey)) {
                    script.echo "came into if so npmScripts contains the key"
                    return true
                }
            }
        } else {
            script.echo "came into not a list condition"
            if (npmScripts[condition.getValue()]) {
                return true
            }
        }
    }
    return false
}
