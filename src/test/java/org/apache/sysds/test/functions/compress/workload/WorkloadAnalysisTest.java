/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.functions.compress.workload;

import java.io.File;

import org.apache.sysds.common.Types.ExecMode;
import org.apache.sysds.test.AutomatedTestBase;
import org.apache.sysds.test.TestConfiguration;
import org.apache.sysds.test.TestUtils;
import org.apache.sysds.utils.Statistics;
import org.junit.Assert;
import org.junit.Test;

public class WorkloadAnalysisTest extends AutomatedTestBase {

	// private static final Log LOG = LogFactory.getLog(WorkloadAnalysisTest.class.getName());

	private final static String TEST_NAME1 = "WorkloadAnalysisLeftMultLoop";
	private final static String TEST_NAME2 = "WorkloadAnalysisRightMultLoop";
	private final static String TEST_DIR = "functions/compress/workload/";
	private final static String TEST_CLASS_DIR = TEST_DIR + WorkloadAnalysisTest.class.getSimpleName() + "/";

	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration(TEST_NAME1, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME1, new String[] {"B"}));
		addTestConfiguration(TEST_NAME2, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME2, new String[] {"B"}));
	}

	@Test
	public void testLeftMultiplicationLoop() {
		runWorkloadAnalysisTest(TEST_NAME1, ExecMode.HYBRID, 1);
	}

	@Test
	public void testRightMultiplicationLoop() {
		runWorkloadAnalysisTest(TEST_NAME2, ExecMode.HYBRID, 1);
	}

	private void runWorkloadAnalysisTest(String testname, ExecMode mode, int compressionCount) {
		ExecMode oldPlatform = setExecMode(mode);

		try {
			loadTestConfiguration(getTestConfiguration(testname));

			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + testname + ".dml";
			programArgs = new String[] {"-stats", "40", "-args", input("X"), "16", output("B")};

			double[][] X = TestUtils.round(getRandomMatrix(10000, 20, 0, 1, 1.0, 7));
			writeInputMatrixWithMTD("X", X, false);

			runTest(true, false, null, -1);

			// check various additional expectations
			long actualCompressionCount = Statistics.getCPHeavyHitterCount("compress");
			Assert.assertEquals(compressionCount, actualCompressionCount);
			Assert.assertTrue(heavyHittersContainsString("compress"));

		}
		finally {
			resetExecMode(oldPlatform);
		}
	}

	@Override
	protected File getConfigTemplateFile() {
		return new File(SCRIPT_DIR + TEST_DIR, "SystemDS-config-compress-workload.xml");
	}
}
