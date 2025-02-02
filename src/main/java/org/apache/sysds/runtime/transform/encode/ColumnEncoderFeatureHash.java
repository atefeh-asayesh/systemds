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

package org.apache.sysds.runtime.transform.encode;

import static org.apache.sysds.runtime.util.UtilFunctions.getEndIndex;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.List;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.sysds.api.DMLScript;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.data.SparseRowVector;
import org.apache.sysds.runtime.matrix.data.FrameBlock;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.util.DependencyTask;
import org.apache.sysds.runtime.util.UtilFunctions;
import org.apache.sysds.utils.Statistics;

/**
 * Class used for feature hashing transformation of frames.
 */
public class ColumnEncoderFeatureHash extends ColumnEncoder {
	private static final long serialVersionUID = 7435806042138687342L;
	private long _K;

	/*
	 * public EncoderFeatureHash(JSONObject parsedSpec, String[] colnames, int clen, int minCol, int maxCol) throws
	 * JSONException { super(null, clen); _colList = TfMetaUtils.parseJsonIDList(parsedSpec, colnames,
	 * TfMethod.HASH.toString(), minCol, maxCol); _K = getK(parsedSpec); }
	 * 
	 */
	public ColumnEncoderFeatureHash(int colID, long K) {
		super(colID);
		_K = K;
	}

	public ColumnEncoderFeatureHash() {
		super(-1);
		_K = 0;
	}

	private long getCode(String key) {
		return (key.hashCode() % _K) + 1;
	}

	@Override
	public void build(FrameBlock in) {
		// do nothing (no meta data other than K)
	}

	@Override
	public List<DependencyTask<?>> getBuildTasks(FrameBlock in) {
		return null;
	}

	@Override
	protected ColumnApplyTask<? extends ColumnEncoder> 
		getSparseTask(FrameBlock in, MatrixBlock out, int outputCol, int startRow, int blk) {
		return new FeatureHashSparseApplyTask(this, in, out, outputCol, startRow, blk);
	}

	@Override
	protected ColumnApplyTask<? extends ColumnEncoder> 
		getSparseTask(MatrixBlock in, MatrixBlock out, int outputCol, int startRow, int blk) {
		throw new NotImplementedException("Sparse FeatureHashing for MatrixBlocks not jet implemented");
	}

	@Override
	public MatrixBlock apply(FrameBlock in, MatrixBlock out, int outputCol, int rowStart, int blk) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		// apply feature hashing column wise
		for(int i = rowStart; i < getEndIndex(in.getNumRows(), rowStart, blk); i++) {
			Object okey = in.get(i, _colID - 1);
			String key = (okey != null) ? okey.toString() : null;
			if(key == null)
				throw new DMLRuntimeException("Missing Value encountered in input Frame for FeatureHash");
			long code = getCode(key);
			out.quickSetValue(i, outputCol, (code >= 0) ? code : Double.NaN);
		}
		if(DMLScript.STATISTICS)
			Statistics.incTransformFeatureHashingApplyTime(System.nanoTime()-t0);
		return out;
	}

	@Override
	public MatrixBlock apply(MatrixBlock in, MatrixBlock out, int outputCol, int rowStart, int blk) {
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		int end = getEndIndex(in.getNumRows(), rowStart, blk);
		// apply feature hashing column wise
		for(int i = rowStart; i < end; i++) {
			Object okey = in.quickGetValueThreadSafe(i, _colID - 1);
			String key = okey.toString();
			long code = getCode(key);
			out.quickSetValue(i, outputCol, (code >= 0) ? code : Double.NaN);
		}
		if(DMLScript.STATISTICS)
			Statistics.incTransformFeatureHashingApplyTime(System.nanoTime()-t0);
		return out;
	}

	@Override
	public void mergeAt(ColumnEncoder other) {
		if(other instanceof ColumnEncoderFeatureHash) {
			assert other._colID == _colID;
			if(((ColumnEncoderFeatureHash) other)._K != 0 && _K == 0)
				_K = ((ColumnEncoderFeatureHash) other)._K;
			return;
		}
		super.mergeAt(other);
	}

	@Override
	public FrameBlock getMetaData(FrameBlock meta) {
		if(!isApplicable())
			return meta;

		meta.ensureAllocatedColumns(1);
		meta.set(0, _colID - 1, String.valueOf(_K));
		return meta;
	}

	@Override
	public void initMetaData(FrameBlock meta) {
		if(meta == null || meta.getNumRows() <= 0)
			return;
		_K = UtilFunctions.parseToLong(meta.get(0, _colID - 1).toString());
	}

	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		super.writeExternal(out);
		out.writeLong(_K);
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException {
		super.readExternal(in);
		_K = in.readLong();
	}

	public static class FeatureHashSparseApplyTask extends ColumnApplyTask<ColumnEncoderFeatureHash>{

		public FeatureHashSparseApplyTask(ColumnEncoderFeatureHash encoder, FrameBlock input, 
				MatrixBlock out, int outputCol, int startRow, int blk) {
			super(encoder, input, out, outputCol, startRow, blk);
		}

		public FeatureHashSparseApplyTask(ColumnEncoderFeatureHash encoder, FrameBlock input, 
				MatrixBlock out, int outputCol) {
			super(encoder, input, out, outputCol);
		}

		@Override
		public Object call() throws Exception {
			if(_out.getSparseBlock() == null)
				return null;
			long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
			int index = _encoder._colID - 1;
			assert _inputF != null;
			for(int r = _startRow; r < getEndIndex(_inputF.getNumRows(), _startRow, _blk); r++){
				SparseRowVector row = (SparseRowVector) _out.getSparseBlock().get(r);
				Object okey = _inputF.get(r, index);
				String key = (okey != null) ? okey.toString() : null;
				if(key == null)
					throw new DMLRuntimeException("Missing Value encountered in input Frame for FeatureHash");
				long code = _encoder.getCode(key);
				row.values()[index] = code;
				row.indexes()[index] = _outputCol;
			}
			if(DMLScript.STATISTICS)
				Statistics.incTransformFeatureHashingApplyTime(System.nanoTime()-t0);
			return null;
		}
	}

}
