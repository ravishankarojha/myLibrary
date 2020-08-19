import com.sap.piper.ConfigurationLoader
import com.sap.piper.GenerateStageDocumentation
import com.sap.piper.QualityCheck
import com.sap.piper.ReportAggregator
import com.sap.piper.Utils

import static com.sap.piper.Prerequisites.checkScript

import groovy.transform.Field

@Field String STEP_NAME = getClass().getName()
@Field String STAGE_NAME = 'mavenExecuteStaticCodeChecks'

@Field Set GENERAL_CONFIG_KEYS = []
@Field Set STEP_CONFIG_KEYS = GENERAL_CONFIG_KEYS
@Field Set PARAMETER_KEYS = STEP_CONFIG_KEYS

/**
 * Execute static code checks for Maven based projects. This stage enforces SAP Cloud SDK specific PMD rulesets as well as SpotBugs include filter.
 *
 */
@GenerateStageDocumentation(defaultStageName = 'mavenExecuteStaticCodeChecks')
void call(Map parameters = [:]) {
    final script = checkScript(this, parameters) ?: null
    def utils = parameters.juStabUtils ?: new Utils()

    def stageName = utils.getStageName(script, parameters, STAGE_NAME)

    piperStageWrapper(stageName: stageName, script: script) {
        mavenExecuteStaticCodeChecks(script: script)
    }
}
