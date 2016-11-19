/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package org.apache.kylin.dict;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.common.util.Dictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Use trie forest to optimize trie dictionary
 * <p>
 * The input data should be in an increase order (sort by org.apache.kylin.dict.ByteComparator)
 * <p>
 * Created by xiefan on 16-10-26.
 */
public class TrieDictionaryForest<T> extends Dictionary<T> {
    private static final long serialVersionUID = 1L;

    private ArrayList<TrieDictionary<T>> trees;

    private ArrayList<ByteArray> valueDivide;

    private ArrayList<Integer> accuOffset; //find tree

    private BytesConverter<T> bytesConvert;

    private int baseId;

    private ArrayList<ByteArray> maxValue;

    private static final Logger logger = LoggerFactory.getLogger(TrieDictionaryForest.class);

    public TrieDictionaryForest() { // default constructor for Writable interface
    }

    public TrieDictionaryForest(ArrayList<TrieDictionary<T>> trees, ArrayList<ByteArray> valueDivide, //
            ArrayList<Integer> accuOffset, BytesConverter<T> bytesConverter, int baseId) {
        this.trees = trees;
        this.valueDivide = valueDivide;
        this.accuOffset = accuOffset;
        this.bytesConvert = bytesConverter;
        this.baseId = baseId;
        initMaxValue();
    }

    @Override
    public int getMinId() {
        if (trees.isEmpty())
            return baseId;
        return trees.get(0).getMinId() + baseId;
    }

    @Override
    public int getMaxId() {
        if (trees.isEmpty())
            return baseId - 1;
        int index = trees.size() - 1;
        int id = accuOffset.get(index) + trees.get(index).getMaxId() + baseId;
        return id;
    }

    @Override
    public int getSizeOfId() {
        if (trees.isEmpty())
            return -1;
        int maxOffset = accuOffset.get(accuOffset.size() - 1);
        TrieDictionary<T> lastTree = trees.get(trees.size() - 1);
        int sizeOfId = BytesUtil.sizeForValue(baseId + maxOffset + lastTree.getMaxId() + 1);
        return sizeOfId;
    }

    @Override
    public int getSizeOfValue() {
        int maxValue = -1;
        for (TrieDictionary<T> tree : trees)
            maxValue = Math.max(maxValue, tree.getSizeOfValue());
        return maxValue;
    }

    // value --> id
    @Override
    protected int getIdFromValueImpl(T value, int roundingFlag) throws IllegalArgumentException {
        byte[] valueBytes = bytesConvert.convertToBytes(value);
        return getIdFromValueBytesImpl(valueBytes, 0, valueBytes.length, roundingFlag);
    }

    @Override
    protected int getIdFromValueBytesImpl(byte[] value, int offset, int len, int roundingFlag) throws IllegalArgumentException {

        int result = _getIdFromValueBytesImpl(value, offset, len, roundingFlag);
        //logger.info("{} => {}, rounding {}", bytesConvert.convertFromBytes(value, offset, len), result, roundingFlag);
        return result;
    }

    // id = tree_inner_offset + accumulate_offset + baseId
    protected int _getIdFromValueBytesImpl(byte[] value, int offset, int len, int roundingFlag) throws IllegalArgumentException {
        int index;
        if (trees.size() == 1) {
            index = 0;
        } else {
            ByteArray search = new ByteArray(value, offset, len);
            index = findIndexByValue(search);
            if (index < 0) {
                if (roundingFlag > 0) {
                    return getMinId(); //searching value smaller than the smallest value in dict
                } else {
                    throw new IllegalArgumentException("Value '" + Bytes.toString(value, offset, len) + "' (" + Bytes.toStringBinary(value, offset, len) + ") not exists!");
                }
            }

            if (roundingFlag > 0) {
                ByteArray maxValueOfTree = maxValue.get(index);
                if (search.compareTo(maxValueOfTree) > 0)
                    index++;
                if (index >= trees.size())
                    throw new IllegalArgumentException("Value '" + Bytes.toString(value, offset, len) + "' (" + Bytes.toStringBinary(value, offset, len) + ") not exists!");
            }
        }
        TrieDictionary<T> tree = trees.get(index);
        int id = tree.getIdFromValueBytes(value, offset, len, roundingFlag);
        id = id + accuOffset.get(index);
        id += baseId;
        return id;
    }

    @Override
    protected T getValueFromIdImpl(int id) throws IllegalArgumentException {
        byte[] data = getValueBytesFromIdImpl(id);
        if (data != null) {
            return bytesConvert.convertFromBytes(data, 0, data.length);
        } else {
            return null;
        }
    }

    @Override
    protected int getValueBytesFromIdImpl(int id, byte[] returnValue, int offset) throws IllegalArgumentException {
        int index = (trees.size() == 1) ? 0 : findIndexById(id);
        int treeInnerOffset = getTreeInnerOffset(id, index);
        TrieDictionary<T> tree = trees.get(index);
        int size = tree.getValueBytesFromIdImpl(treeInnerOffset, returnValue, offset);
        return size;
    }

    @Override
    protected byte[] getValueBytesFromIdImpl(int id) throws IllegalArgumentException {
        int index = (trees.size() == 1) ? 0 : findIndexById(id);
        int treeInnerOffset = getTreeInnerOffset(id, index);
        TrieDictionary<T> tree = trees.get(index);
        byte[] result = tree.getValueBytesFromId(treeInnerOffset);
        return result;
    }

    private int getTreeInnerOffset(int id, int index) {
        id -= baseId;
        id = id - accuOffset.get(index);
        return id;
    }

    @Override
    public void dump(PrintStream out) {
        out.println("TrieDictionaryForest");
        out.println("baseId:" + baseId);
        StringBuilder sb = new StringBuilder();
        sb.append("value divide:");
        for (ByteArray ba : valueDivide)
            sb.append(bytesConvert.convertFromBytes(ba.array(), 0, ba.length()) + " ");
        sb.append("\noffset divide:");
        for (Integer offset : accuOffset)
            sb.append(offset + " ");
        out.println(sb.toString());
        for (int i = 0; i < trees.size(); i++) {
            out.println("----tree " + i + "--------");
            trees.get(i).dump(out);
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
        writeHead(out);
        writeBody(out);
    }

    private void writeHead(DataOutput out) throws IOException {
        ByteArrayOutputStream byteBuf = new ByteArrayOutputStream();
        DataOutputStream headOut = new DataOutputStream(byteBuf);
        headOut.writeInt(baseId);
        headOut.writeUTF(bytesConvert == null ? "" : bytesConvert.getClass().getName());
        //write accuOffset
        headOut.writeInt(accuOffset.size());
        for (int i = 0; i < accuOffset.size(); i++)
            headOut.writeInt(accuOffset.get(i));
        //write valueDivide
        headOut.writeInt(valueDivide.size());
        for (int i = 0; i < valueDivide.size(); i++) {
            ByteArray ba = valueDivide.get(i);
            byte[] byteStr = ba.toBytes();
            headOut.writeInt(byteStr.length);
            headOut.write(byteStr);
        }
        //write tree size
        headOut.writeInt(trees.size());
        headOut.close();
        byte[] head = byteBuf.toByteArray();
        //output
        out.writeInt(head.length);
        out.write(head);
    }

    private void writeBody(DataOutput out) throws IOException {
        for (int i = 0; i < trees.size(); i++) {
            TrieDictionary<T> tree = trees.get(i);
            tree.write(out);
        }
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        try {
            @SuppressWarnings("unused")
            int headSize = in.readInt();
            this.baseId = in.readInt();
            String converterName = in.readUTF();
            if (converterName.isEmpty() == false)
                this.bytesConvert = ClassUtil.forName(converterName, BytesConverter.class).newInstance();
            //init accuOffset
            int accuSize = in.readInt();
            this.accuOffset = new ArrayList<>();
            for (int i = 0; i < accuSize; i++) {
                accuOffset.add(in.readInt());
            }
            //init valueDivide
            int valueDivideSize = in.readInt();
            this.valueDivide = new ArrayList<>();
            for (int i = 0; i < valueDivideSize; i++) {
                int length = in.readInt();
                byte[] buffer = new byte[length];
                in.readFully(buffer);
                valueDivide.add(new ByteArray(buffer, 0, buffer.length));
            }
            int treeSize = in.readInt();
            this.trees = new ArrayList<>();
            for (int i = 0; i < treeSize; i++) {
                TrieDictionary<T> dict = new TrieDictionary<>();
                dict.readFields(in);
                trees.add(dict);
            }
            initMaxValue();
        } catch (Exception e) {
            if (e instanceof RuntimeException)
                throw (RuntimeException) e;
            else
                throw new RuntimeException(e);
        }

    }

    @Override
    public boolean contains(Dictionary other) {
        if (other.getSize() > this.getSize()) {
            return false;
        }

        for (int i = other.getMinId(); i <= other.getMaxId(); ++i) {
            T v = (T) other.getValueFromId(i);
            if (!this.containsValue(v)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + baseId;
        result = prime * result + ((trees == null) ? 0 : trees.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        TrieDictionaryForest other = (TrieDictionaryForest) obj;
        if (baseId != other.baseId)
            return false;
        if (trees == null) {
            if (other.trees != null)
                return false;
        } else if (!trees.equals(other.trees))
            return false;
        return true;
    }

    List<TrieDictionary<T>> getTrees() {
        return Collections.unmodifiableList(this.trees);
    }

    BytesConverter<T> getBytesConvert() {
        return bytesConvert;
    }

    private int findIndexByValue(ByteArray value) {
        int index = lowerBound(value, this.valueDivide);
        return index;
    }

    private int findIndexById(Integer id) {
        id -= baseId;
        int index = lowerBound(id, this.accuOffset);
        if (index < 0) {
            throw new IllegalArgumentException("Tree Not Found. index < 0");
        }
        return index;
    }

    private static <K extends Comparable> int lowerBound(K lookfor, ArrayList<K> list) {
        if (list == null || list.isEmpty())
            return -1; // not found
        int left = 0;
        int right = list.size() - 1;
        int mid = 0;
        boolean found = false;
        int comp = 0;
        while (!found && left <= right) {
            mid = left + (right - left) / 2;
            comp = lookfor.compareTo(list.get(mid));
            if (comp < 0)
                right = mid - 1;
            else if (comp > 0)
                left = mid + 1;
            else
                found = true;
        }
        if (found) {
            return mid;
        } else {
            return Math.min(left, right); // value may be bigger than the right tree (could return -1)
        }
    }

    private void initMaxValue() throws IllegalStateException {
        this.maxValue = new ArrayList<>();
        if (this.trees == null || trees.isEmpty()) {
            logger.info("Trees not init yet or trees size is zero. Could not init max value of each tree");
            return;
        }
        for (int i = 0; i < trees.size(); i++) {
            T curTreeMax = trees.get(i).getValueFromId(trees.get(i).getMaxId());
            byte[] b1 = bytesConvert.convertToBytes(curTreeMax);
            ByteArray ba1 = new ByteArray(b1, 0, b1.length);
            this.maxValue.add(ba1);
        }
    }

    public static void main(String[] args) {
        ArrayList<String> list = new ArrayList<>();
        list.add("一");
        list.add("二");
        list.add("三");
        list.add("");
        list.add("part");
        list.add("par");
        list.add("partition");
        list.add("party");
        list.add("parties");
        list.add("paint");
        Collections.sort(list);
        for (String str : list) {
            System.out.println("found value:" + str + " index:" + lowerBound(str, list));
        }
    }

}
