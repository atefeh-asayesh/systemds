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

package org.apache.sysds.runtime.compress.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.compress.CompressedMatrixBlock;
import org.apache.sysds.runtime.compress.CompressedMatrixBlockFactory;
import org.apache.sysds.runtime.compress.CompressionSettings;
import org.apache.sysds.runtime.compress.DMLCompressionException;
import org.apache.sysds.runtime.compress.colgroup.AColGroup;
import org.apache.sysds.runtime.compress.colgroup.ColGroupConst;
import org.apache.sysds.runtime.compress.colgroup.dictionary.ADictionary;
import org.apache.sysds.runtime.compress.colgroup.dictionary.MatrixBlockDictionary;
import org.apache.sysds.runtime.data.SparseBlock;
import org.apache.sysds.runtime.functionobjects.Divide;
import org.apache.sysds.runtime.functionobjects.Minus;
import org.apache.sysds.runtime.functionobjects.Minus1Multiply;
import org.apache.sysds.runtime.functionobjects.MinusMultiply;
import org.apache.sysds.runtime.functionobjects.Multiply;
import org.apache.sysds.runtime.functionobjects.Plus;
import org.apache.sysds.runtime.functionobjects.PlusMultiply;
import org.apache.sysds.runtime.functionobjects.ValueFunction;
import org.apache.sysds.runtime.matrix.data.LibMatrixBincell;
import org.apache.sysds.runtime.matrix.data.LibMatrixBincell.BinaryAccessType;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.apache.sysds.runtime.matrix.data.MatrixValue;
import org.apache.sysds.runtime.matrix.operators.BinaryOperator;
import org.apache.sysds.runtime.matrix.operators.LeftScalarOperator;
import org.apache.sysds.runtime.matrix.operators.RightScalarOperator;
import org.apache.sysds.runtime.matrix.operators.ScalarOperator;
import org.apache.sysds.runtime.util.CommonThreadPool;

public class CLALibBinaryCellOp {

	private static final Log LOG = LogFactory.getLog(CLALibBinaryCellOp.class.getName());

	public static MatrixBlock binaryOperations(BinaryOperator op, CompressedMatrixBlock m1, MatrixBlock that,
		MatrixBlock result) {
		try {
			if(that.getNumRows() == 1 && that.getNumColumns() == 1) {
				ScalarOperator sop = new RightScalarOperator(op.fn, that.getValue(0, 0), op.getNumThreads());
				return CLALibScalar.scalarOperations(sop, m1, result);
			}
			if(that.isEmpty())
				return binaryOperationsEmpty(op, m1, that, result);
			that = CompressedMatrixBlock.getUncompressed(that, "Decompressing right side in BinaryOps");
			LibMatrixBincell.isValidDimensionsBinary(m1, that);
			BinaryAccessType atype = LibMatrixBincell.getBinaryAccessType(m1, that);
			return selectProcessingBasedOnAccessType(op, m1, that, result, atype, false);
		}
		catch(Exception e) {
			throw new DMLCompressionException("Failed to perform compressed binary operation: " + op, e);
		}
	}

	public static MatrixBlock binaryOperationsLeft(BinaryOperator op, CompressedMatrixBlock m1, MatrixBlock that,
		MatrixBlock result) {
		if(that.getNumRows() == 1 && that.getNumColumns() == 1) {
			ScalarOperator sop = new LeftScalarOperator(op.fn, that.getValue(0, 0), op.getNumThreads());
			return CLALibScalar.scalarOperations(sop, m1, result);
		}
		if(that.isEmpty())
			throw new NotImplementedException("Not handling left empty yet");

		that = CompressedMatrixBlock.getUncompressed(that, "Decompressing left side in BinaryOps");
		LibMatrixBincell.isValidDimensionsBinary(that, m1);
		BinaryAccessType atype = LibMatrixBincell.getBinaryAccessType(that, m1);
		return selectProcessingBasedOnAccessType(op, m1, that, result, atype, true);
	}

	private static MatrixBlock binaryOperationsEmpty(BinaryOperator op, CompressedMatrixBlock m1, MatrixBlock that,
		MatrixBlock result) {
		final ValueFunction fn = op.fn;
		if((m1.getNumRows() == that.getNumRows() && m1.getNumColumns() == that.getNumColumns()) ||
			m1.getNumColumns() == that.getNumColumns()) {

			if(fn instanceof Multiply)
				result = CompressedMatrixBlockFactory.createConstant(m1.getNumRows(), m1.getNumColumns(), 0);
			else if(fn instanceof Minus1Multiply)
				result = CompressedMatrixBlockFactory.createConstant(m1.getNumRows(), m1.getNumColumns(), 1);
			else if(fn instanceof Minus || fn instanceof Plus || fn instanceof MinusMultiply ||
				fn instanceof PlusMultiply) {
				CompressedMatrixBlock ret = new CompressedMatrixBlock();
				ret.copy(m1);
				return ret;
			}
			else
				throw new NotImplementedException("Function Type: " + fn);

			return result;
		}
		else {
			final long lr = m1.getNumRows();
			final long rr = that.getNumRows();
			final long lc = m1.getNumColumns();
			final long rc = that.getNumColumns();
			throw new NotImplementedException(
				"Not Implemented sizes: left(" + lr + ", " + lc + ") right(" + rr + ", " + rc + ")");
		}
	}

	private static MatrixBlock selectProcessingBasedOnAccessType(BinaryOperator op, CompressedMatrixBlock m1,
		MatrixBlock that, MatrixBlock result, BinaryAccessType atype, boolean left) {
		final int outRows = m1.getNumRows();
		final int outCols = m1.getNumColumns();
		// TODO optimize to allow for sparse outputs.
		final int outCells = outRows * outCols;
		if(atype == BinaryAccessType.MATRIX_COL_VECTOR) {
			if(result != null)
				result.reset(outRows, Math.max(outCols, that.getNumColumns()), outCells);
			else
				result = new MatrixBlock(outRows, Math.max(outCols, that.getNumColumns()), outCells);

			MatrixBlock d_compressed = m1.getCachedDecompressed();
			if(d_compressed != null) {
				if(left)
					LibMatrixBincell.bincellOp(that, d_compressed, result, op);
				else
					LibMatrixBincell.bincellOp(d_compressed, that, result, op);
				return result;
			}
			else
				return binaryMVCol(m1, that, op, left);

		}
		else if(atype == BinaryAccessType.MATRIX_MATRIX) {
			if(result != null)
				result.reset(outRows, outCols, outCells);
			else
				result = new MatrixBlock(outRows, outCols, outCells);

			MatrixBlock d_compressed = m1.getCachedDecompressed();
			if(d_compressed == null)
				d_compressed = m1.getUncompressed("MatrixMatrix " + op);

			if(left)
				LibMatrixBincell.bincellOp(that, d_compressed, result, op);
			else
				LibMatrixBincell.bincellOp(d_compressed, that, result, op);
			return result;
		}
		else if(isSupportedBinaryCellOp(op.fn))
			return bincellOp(m1, that, setupCompressedReturnMatrixBlock(m1, result), op, left);
		else {
			return CompressedMatrixBlock.getUncompressed(m1, "BinaryOp: " + op.fn).binaryOperations(op, that, result);
		}
	}

	private static boolean isSupportedBinaryCellOp(ValueFunction fn) {
		return fn instanceof Multiply || fn instanceof Divide || fn instanceof Plus || fn instanceof Minus ||
			fn instanceof MinusMultiply || fn instanceof PlusMultiply;
	}

	private static CompressedMatrixBlock setupCompressedReturnMatrixBlock(CompressedMatrixBlock m1,
		MatrixValue result) {
		CompressedMatrixBlock ret = null;
		if(result == null || !(result instanceof CompressedMatrixBlock))
			ret = new CompressedMatrixBlock(m1.getNumRows(), m1.getNumColumns());
		else {
			ret = (CompressedMatrixBlock) result;
			ret.reset(m1.getNumRows(), m1.getNumColumns());
		}
		return ret;
	}

	private static MatrixBlock bincellOp(CompressedMatrixBlock m1, MatrixBlock m2, CompressedMatrixBlock ret,
		BinaryOperator op, boolean left) {
		if(isValidForOverlappingBinaryCellOperations(m1, op))
			overlappingBinaryCellOp(m1, m2, ret, op, left);
		else
			nonOverlappingBinaryCellOp(m1, m2, ret, op, left);
		return ret;

	}

	private static void nonOverlappingBinaryCellOp(CompressedMatrixBlock m1, MatrixBlock m2, CompressedMatrixBlock ret,
		BinaryOperator op, boolean left) {

		BinaryAccessType atype = LibMatrixBincell.getBinaryAccessType(m1, m2);
		switch(atype) {
			case MATRIX_ROW_VECTOR:
				// Verify if it is okay to include all OuterVectorVector ops here.
				binaryMVRow(m1, m2, ret, op, left);
				return;
			case OUTER_VECTOR_VECTOR:
				if(m2.getNumRows() == 1 && m2.getNumColumns() == 1) {
					CLALibScalar.scalarOperations(new RightScalarOperator(op.fn, m2.quickGetValue(0, 0)), m1, ret);
				}
				return;
			default:
				LOG.warn("Inefficient Decompression for " + op + "  " + atype);
				m1.decompress().binaryOperations(op, m2, ret);

		}
	}

	private static boolean isValidForOverlappingBinaryCellOperations(CompressedMatrixBlock m1, BinaryOperator op) {
		return m1.isOverlapping() && !(op.fn instanceof Multiply || op.fn instanceof Divide);
	}

	private static void overlappingBinaryCellOp(CompressedMatrixBlock m1, MatrixBlock m2, CompressedMatrixBlock ret,
		BinaryOperator op, boolean left) {
		if(op.fn instanceof Plus || op.fn instanceof Minus)
			binaryMVPlusStack(m1, m2, ret, op, left);
		else
			throw new NotImplementedException(op + " not implemented for Overlapping CLA");

	}

	public static CompressedMatrixBlock binaryMVRow(CompressedMatrixBlock m1, double[] v, CompressedMatrixBlock ret,
		BinaryOperator op, boolean left) {

		List<AColGroup> oldColGroups = m1.getColGroups();

		if(ret == null)
			ret = new CompressedMatrixBlock(m1.getNumRows(), m1.getNumColumns());

		boolean sparseSafe = true;
		for(double x : v) {
			if(op.fn.execute(0.0, x) != 0.0) {
				sparseSafe = false;
				break;
			}
		}

		List<AColGroup> newColGroups = new ArrayList<>(oldColGroups.size());
		int k = op.getNumThreads();
		ExecutorService pool = CommonThreadPool.get(k);
		ArrayList<BinaryMVRowTask> tasks = new ArrayList<>();
		try {
			for(AColGroup grp : oldColGroups) {
				tasks.add(new BinaryMVRowTask(grp, v, sparseSafe, op, left));
			}

			for(Future<AColGroup> f : pool.invokeAll(tasks))
				newColGroups.add(f.get());

			pool.shutdown();
		}
		catch(InterruptedException | ExecutionException e) {
			e.printStackTrace();
			throw new DMLRuntimeException(e);
		}

		ret.allocateColGroupList(newColGroups);
		ret.setNonZeros(m1.getNumColumns() * m1.getNumRows());
		return ret;
	}

	protected static CompressedMatrixBlock binaryMVRow(CompressedMatrixBlock m1, MatrixBlock m2,
		CompressedMatrixBlock ret, BinaryOperator op, boolean left) {
		return binaryMVRow(m1, forceMatrixBlockToDense(m2), ret, op, left);

	}

	private static double[] forceMatrixBlockToDense(MatrixBlock m2) {
		double[] v;
		if(m2.isInSparseFormat()) {
			SparseBlock sb = m2.getSparseBlock();
			if(sb == null) {
				throw new DMLRuntimeException("Unknown matrix block type");
			}
			else {
				double[] spV = sb.values(0);
				int[] spI = sb.indexes(0);
				v = new double[m2.getNumColumns()];
				for(int i = sb.pos(0); i < sb.size(0); i++) {
					v[spI[i]] = spV[i];
				}
			}
		}
		else
			v = m2.getDenseBlockValues();

		return v;

	}

	protected static CompressedMatrixBlock binaryMVPlusStack(CompressedMatrixBlock m1, MatrixBlock m2,
		CompressedMatrixBlock ret, BinaryOperator op, boolean left) {
		if(m2.isEmpty())
			return m1;
		List<AColGroup> oldColGroups = m1.getColGroups();
		final int size = oldColGroups.size();
		List<AColGroup> newColGroups = new ArrayList<>(size);
		int smallestIndex = 0;
		int smallestSize = Integer.MAX_VALUE;
		final int nCol = m1.getNumColumns();
		for(int i = 0; i < size; i++) {
			final AColGroup g = oldColGroups.get(i);
			final int newSize = g.getNumValues();
			newColGroups.add(g);
			if(newSize < smallestSize && g.getNumCols() == nCol) {
				smallestIndex = i;
				smallestSize = newSize;
			}
		}
		if(smallestSize == Integer.MAX_VALUE) {
			int[] colIndexes = new int[nCol];
			for(int i = 0; i < nCol; i++)
				colIndexes[i] = i;
			ADictionary newDict = new MatrixBlockDictionary(m2);
			newColGroups.add(new ColGroupConst(colIndexes, m1.getNumRows(), newDict));
		}
		else {
			AColGroup g = newColGroups.get(smallestIndex).binaryRowOp(op, m2.getDenseBlockValues(), false, left);
			newColGroups.set(smallestIndex, g);
		}

		ret.allocateColGroupList(newColGroups);
		ret.setOverlapping(true);
		ret.setNonZeros(-1);
		return ret;
	}

	private static MatrixBlock binaryMVCol(CompressedMatrixBlock m1, MatrixBlock m2, BinaryOperator op, boolean left) {

		MatrixBlock ret = new MatrixBlock(m1.getNumRows(), m1.getNumColumns(), false, -1).allocateBlock();

		final int blkz = CompressionSettings.BITMAP_BLOCK_SZ;
		final int k = op.getNumThreads();
		long nnz = 0;

		if(k <= 1) {
			for(int i = 0; i * blkz < m1.getNumRows(); i++) {
				if(left)
					nnz += new BinaryMVColLeftTask(m1, m2, ret, i * blkz, Math.min(m1.getNumRows(), (i + 1) * blkz), op)
						.call();
				else
					nnz += new BinaryMVColTask(m1, m2, ret, i * blkz, Math.min(m1.getNumRows(), (i + 1) * blkz), op)
						.call();

			}
		}
		else {
			ExecutorService pool = CommonThreadPool.get(op.getNumThreads());
			ArrayList<Callable<Integer>> tasks = new ArrayList<>();
			try {
				for(int i = 0; i * blkz < m1.getNumRows(); i++) {
					if(left)
						tasks.add(new BinaryMVColLeftTask(m1, m2, ret, i * blkz,
							Math.min(m1.getNumRows(), (i + 1) * blkz), op));
					else
						tasks.add(
							new BinaryMVColTask(m1, m2, ret, i * blkz, Math.min(m1.getNumRows(), (i + 1) * blkz), op));

				}
				for(Future<Integer> f : pool.invokeAll(tasks))
					nnz += f.get();
				pool.shutdown();
			}
			catch(InterruptedException | ExecutionException e) {
				e.printStackTrace();
				throw new DMLRuntimeException(e);
			}
		}
		ret.setNonZeros(nnz);

		return ret;
	}

	private static class BinaryMVColTask implements Callable<Integer> {
		private final int _rl;
		private final int _ru;
		private final CompressedMatrixBlock _m1;
		private final MatrixBlock _m2;
		private final MatrixBlock _ret;
		private final BinaryOperator _op;

		protected BinaryMVColTask(CompressedMatrixBlock m1, MatrixBlock m2, MatrixBlock ret, int rl, int ru,
			BinaryOperator op) {
			_m1 = m1;
			_m2 = m2;
			_ret = ret;
			_op = op;
			_rl = rl;
			_ru = ru;
		}

		@Override
		public Integer call() {
			// unsafe decompress, since we count nonzeros afterwards.
			for(AColGroup g : _m1.getColGroups())
				g.decompressToBlockUnSafe(_ret, _rl, _ru);

			if(_m2.isInSparseFormat())
				throw new NotImplementedException("Not Implemented sparse Format execution for MM.");
			else {
				int offset = _rl * _m1.getNumColumns();
				double[] _retDense = _ret.getDenseBlockValues();
				double[] _m2Dense = _m2.getDenseBlockValues();
				for(int row = _rl; row < _ru; row++) {
					double vr = _m2Dense[row];
					for(int col = 0; col < _m1.getNumColumns(); col++) {
						double v = _op.fn.execute(_retDense[offset], vr);
						_retDense[offset] = v;
						offset++;
					}
				}

				return _ret.getNumColumns() * _ret.getNumRows();
			}
		}
	}

	private static class BinaryMVColLeftTask implements Callable<Integer> {
		private final int _rl;
		private final int _ru;
		private final CompressedMatrixBlock _m1;
		private final MatrixBlock _m2;
		private final MatrixBlock _ret;
		private final BinaryOperator _op;

		protected BinaryMVColLeftTask(CompressedMatrixBlock m1, MatrixBlock m2, MatrixBlock ret, int rl, int ru,
			BinaryOperator op) {
			_m1 = m1;
			_m2 = m2;
			_ret = ret;
			_op = op;
			_rl = rl;
			_ru = ru;
		}

		@Override
		public Integer call() {
			// unsafe decompress, since we count nonzeros afterwards.
			for(AColGroup g : _m1.getColGroups())
				g.decompressToBlockUnSafe(_ret, _rl, _ru);

			if(_m2.isInSparseFormat())
				throw new NotImplementedException("Not Implemented sparse Format execution for MM.");
			else {
				int offset = _rl * _m1.getNumColumns();
				double[] _retDense = _ret.getDenseBlockValues();
				double[] _m2Dense = _m2.getDenseBlockValues();
				for(int row = _rl; row < _ru; row++) {
					double vr = _m2Dense[row];
					for(int col = 0; col < _m1.getNumColumns(); col++) {
						double v = _op.fn.execute(vr, _retDense[offset]);
						_retDense[offset] = v;
						offset++;
					}
				}

				return _ret.getNumColumns() * _ret.getNumRows();
			}
		}
	}

	private static class BinaryMVRowTask implements Callable<AColGroup> {
		private final AColGroup _group;
		private final double[] _v;
		private final boolean _sparseSafe;
		private final BinaryOperator _op;
		private final boolean _left;

		protected BinaryMVRowTask(AColGroup group, double[] v, boolean sparseSafe, BinaryOperator op, boolean left) {
			_group = group;
			_v = v;
			_op = op;
			_sparseSafe = sparseSafe;
			_left = left;
		}

		@Override
		public AColGroup call() {
			return _group.binaryRowOp(_op, _v, _sparseSafe, _left);
		}
	}
}
