/*-
 * Copyright (C) 2006-2009 Erik Larsson
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.takari.jdkget.osx.hfs.plus;

import io.takari.jdkget.osx.hfs.AllocationFile;
import io.takari.jdkget.osx.hfs.AttributesFile;
import io.takari.jdkget.osx.hfs.HFSVolume;
import io.takari.jdkget.osx.hfs.HotFilesFile;
import io.takari.jdkget.osx.hfs.Journal;
import io.takari.jdkget.osx.hfs.io.ForkFilter;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonBTHeaderNode;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonBTHeaderRecord;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonBTNodeDescriptor;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSCatalogIndexNode;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSCatalogKey;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSCatalogLeafNode;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSCatalogLeafRecord;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSCatalogNodeID;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSCatalogString;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSExtentIndexNode;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSExtentKey;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSExtentLeafNode;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSForkData;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSForkType;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSVolumeHeader;
import io.takari.jdkget.osx.hfs.types.hfscommon.CommonHFSCatalogNodeID.ReservedID;
import io.takari.jdkget.osx.hfs.types.hfsplus.BTHeaderRec;
import io.takari.jdkget.osx.hfs.types.hfsplus.BTNodeDescriptor;
import io.takari.jdkget.osx.hfs.types.hfsplus.HFSCatalogNodeID;
import io.takari.jdkget.osx.hfs.types.hfsplus.HFSPlusCatalogKey;
import io.takari.jdkget.osx.hfs.types.hfsplus.HFSPlusExtentKey;
import io.takari.jdkget.osx.hfs.types.hfsplus.HFSPlusVolumeHeader;
import io.takari.jdkget.osx.hfs.types.hfsplus.HFSUniStr255;
import io.takari.jdkget.osx.io.Readable;
import io.takari.jdkget.osx.io.ReadableRandomAccessStream;
import io.takari.jdkget.osx.io.ReadableRandomAccessSubstream;
import io.takari.jdkget.osx.io.SynchronizedReadableRandomAccess;
import io.takari.jdkget.osx.util.Util;

/**
 * @author <a href="http://www.catacombae.org/" target="_top">Erik Larsson</a>
 */
public class HFSPlusVolume extends HFSVolume {
  private static final CommonHFSCatalogString EMPTY_STRING =
    CommonHFSCatalogString.createHFSPlus(new HFSUniStr255(""));

  private final HFSPlusAllocationFile allocationFile;
  private final HFSPlusJournal journal;
  private final AttributesFile attributesFile;

  public HFSPlusVolume(ReadableRandomAccessStream hfsFile,
    boolean cachingEnabled) {
    this(hfsFile, cachingEnabled, HFSPlusVolumeHeader.SIGNATURE_HFS_PLUS);
  }

  protected HFSPlusVolume(ReadableRandomAccessStream hfsFile,
    boolean cachingEnabled, short volumeHeaderSignature) {
    super(hfsFile, cachingEnabled);

    final HFSPlusVolumeHeader volumeHeader = getHFSPlusVolumeHeader();
    if (volumeHeader.getSignature() != volumeHeaderSignature) {
      throw new RuntimeException("Invalid volume header signature " +
        "(expected: 0x" +
        Util.toHexStringBE(volumeHeaderSignature) + " actual: 0x" +
        Util.toHexStringBE(volumeHeader.getSignature()) + ").");
    }

    this.allocationFile = createAllocationFile();
    this.journal = new HFSPlusJournal(this);

    if (volumeHeader.getAttributesFile().getExtents().getExtentDescriptors()[0].getBlockCount() == 0) {
      /* TODO: Is this even valid? */
      this.attributesFile = null;
    } else {
      this.attributesFile = new AttributesFile(this);
    }
  }

  SynchronizedReadableRandomAccess getBackingStream() {
    return hfsFile;
  }

  public final HFSPlusVolumeHeader getHFSPlusVolumeHeader() {
    //System.err.println("getHFSPlusVolumeHeader()");
    byte[] currentBlock = new byte[512];
    //System.err.println("  hfsFile.seek(" + (fsOffset + 1024) + ")");
    //System.err.println("  hfsFile.read(byte[" + currentBlock.length +
    //        "])");
    hfsFile.readFrom(1024, currentBlock);
    return new HFSPlusVolumeHeader(currentBlock);
  }

  @Override
  public CommonHFSVolumeHeader getVolumeHeader() {
    return CommonHFSVolumeHeader.create(getHFSPlusVolumeHeader());
  }

  private HFSPlusAllocationFile createAllocationFile() {
    HFSPlusVolumeHeader vh = getHFSPlusVolumeHeader();

    CommonHFSForkData allocationFileFork =
      CommonHFSForkData.create(vh.getAllocationFile());

    ForkFilter allocationFileStream = new ForkFilter(
      ForkFilter.ForkType.DATA,
      getCommonHFSCatalogNodeID(ReservedID.ALLOCATION_FILE).toLong(),
      allocationFileFork,
      extentsOverflowFile,
      new ReadableRandomAccessSubstream(hfsFile),
      0, Util.unsign(vh.getBlockSize()), 0);

    return new HFSPlusAllocationFile(this, allocationFileStream);
  }

  @Override
  public AllocationFile getAllocationFile() {
    return allocationFile;
  }

  @Override
  public boolean hasAttributesFile() {
    return attributesFile != null;
  }

  @Override
  public AttributesFile getAttributesFile() {
    return attributesFile;
  }

  @Override
  public boolean hasJournal() {
    return getHFSPlusVolumeHeader().getAttributeVolumeJournaled();
  }

  @Override
  public Journal getJournal() {
    if (hasJournal())
      return journal;
    else
      return null;
  }

  @Override
  public boolean hasHotFilesFile() {
    return false; // TODO
  }

  @Override
  public HotFilesFile getHotFilesFile() {
    return null; // TODO
  }

  @Override
  public CommonHFSCatalogNodeID getCommonHFSCatalogNodeID(
    ReservedID requestedNodeID) {

    return CommonHFSCatalogNodeID.getHFSPlusReservedID(requestedNodeID);
  }

  @Override
  public CommonHFSCatalogNodeID createCommonHFSCatalogNodeID(int cnid) {
    return CommonHFSCatalogNodeID.create(new HFSCatalogNodeID(cnid));
  }

  @Override
  public CommonHFSExtentKey createCommonHFSExtentKey(boolean isResource,
    int cnid, long startBlock) {
    if (startBlock > 0xFFFFFFFFL) {
      throw new IllegalArgumentException("Value of 'startBlock' is too " +
        "large for an HFS+ extent key.");
    }

    return CommonHFSExtentKey.create(new HFSPlusExtentKey(
      isResource ? HFSPlusExtentKey.RESOURCE_FORK : HFSPlusExtentKey.DATA_FORK,
      new HFSCatalogNodeID(cnid),
      (int) startBlock));
  }

  @Override
  public CommonHFSCatalogString getEmptyString() {
    return EMPTY_STRING;
  }

  @Override
  public String decodeString(CommonHFSCatalogString str) {
    if (str instanceof CommonHFSCatalogString.HFSPlusImplementation) {
      CommonHFSCatalogString.HFSPlusImplementation hStr =
        (CommonHFSCatalogString.HFSPlusImplementation) str;
      return new String(hStr.getInternal().getUnicode());
    } else
      throw new RuntimeException("Invalid string type: " +
        str.getClass());
  }

  @Override
  public CommonHFSCatalogString encodeString(String str) {
    return CommonHFSCatalogString.createHFSPlus(
      new HFSUniStr255(str));
  }

  @Override
  public void close() {
    allocationFile.close();
    super.close();
  }

  @Override
  public CommonBTHeaderNode createCommonBTHeaderNode(byte[] currentNodeData,
    int offset, int nodeSize) {
    return CommonBTHeaderNode.createHFSPlus(currentNodeData, offset,
      nodeSize);
  }

  @Override
  public CommonBTNodeDescriptor readNodeDescriptor(Readable rd) {
    byte[] data = new byte[BTNodeDescriptor.length()];
    rd.readFully(data);
    final BTNodeDescriptor btnd = new BTNodeDescriptor(data, 0);

    return CommonBTNodeDescriptor.create(btnd);
  }

  @Override
  public CommonBTHeaderRecord readHeaderRecord(Readable rd) {
    byte[] data = new byte[BTHeaderRec.length()];
    rd.readFully(data);
    BTHeaderRec bthr = new BTHeaderRec(data, 0);

    return CommonBTHeaderRecord.create(bthr);
  }

  @Override
  public CommonBTNodeDescriptor createCommonBTNodeDescriptor(
    byte[] currentNodeData, int offset) {
    final BTNodeDescriptor btnd =
      new BTNodeDescriptor(currentNodeData, offset);
    return CommonBTNodeDescriptor.create(btnd);
  }

  @Override
  public CommonHFSCatalogIndexNode newCatalogIndexNode(byte[] data,
    int offset, int nodeSize) {
    return CommonHFSCatalogIndexNode.createHFSPlus(data, offset, nodeSize);
  }

  @Override
  public CommonHFSCatalogKey newCatalogKey(CommonHFSCatalogNodeID nodeID,
    CommonHFSCatalogString searchString) {
    return CommonHFSCatalogKey.create(new HFSPlusCatalogKey(
      new HFSCatalogNodeID((int) nodeID.toLong()),
      new HFSUniStr255(searchString.getStructBytes(), 0)));
  }

  @Override
  public CommonHFSCatalogLeafNode newCatalogLeafNode(byte[] data, int offset,
    int nodeSize) {
    return CommonHFSCatalogLeafNode.createHFSPlus(data, offset, nodeSize);
  }

  @Override
  public CommonHFSCatalogLeafRecord newCatalogLeafRecord(byte[] data,
    int offset) {
    return CommonHFSCatalogLeafRecord.createHFSPlus(data, offset,
      data.length - offset);
  }

  @Override
  public CommonHFSExtentIndexNode createCommonHFSExtentIndexNode(
    byte[] currentNodeData, int offset, int nodeSize) {
    return CommonHFSExtentIndexNode.createHFSPlus(currentNodeData, offset,
      nodeSize);
  }

  @Override
  public CommonHFSExtentLeafNode createCommonHFSExtentLeafNode(
    byte[] currentNodeData, int offset, int nodeSize) {
    return CommonHFSExtentLeafNode.createHFSPlus(currentNodeData, offset,
      nodeSize);
  }

  @Override
  public CommonHFSExtentKey createCommonHFSExtentKey(
    CommonHFSForkType forkType, CommonHFSCatalogNodeID fileID,
    int startBlock) {

    final byte forkTypeByte;
    switch (forkType) {
      case DATA_FORK:
        forkTypeByte = HFSPlusExtentKey.DATA_FORK;
        break;
      case RESOURCE_FORK:
        forkTypeByte = HFSPlusExtentKey.RESOURCE_FORK;
        break;
      default:
        throw new RuntimeException("Invalid fork type");
    }

    HFSPlusExtentKey key = new HFSPlusExtentKey(forkTypeByte,
      new HFSCatalogNodeID((int) fileID.toLong()), startBlock);
    return CommonHFSExtentKey.create(key);
  }
}
