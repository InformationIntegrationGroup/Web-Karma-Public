/*******************************************************************************
 * Copyright 2012 University of Southern California
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * This code was developed by the Information Integration Group as part 
 * of the Karma project at the Information Sciences Institute of the 
 * University of Southern California.  For more information, publications, 
 * and related projects, please see: http://www.isi.edu/integration
 ******************************************************************************/

package edu.isi.karma.controller.command.alignment;

import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.isi.karma.controller.command.Command;
import edu.isi.karma.controller.command.CommandException;
import edu.isi.karma.controller.command.alignment.SetMetaPropertyCommandFactory.METAPROPERTY_NAME;
import edu.isi.karma.controller.update.EmptyUpdate;
import edu.isi.karma.controller.update.ErrorUpdate;
import edu.isi.karma.controller.update.SVGAlignmentUpdate_ForceKarmaLayout;
import edu.isi.karma.controller.update.SemanticTypesUpdate;
import edu.isi.karma.controller.update.UpdateContainer;
import edu.isi.karma.modeling.alignment.Alignment;
import edu.isi.karma.modeling.alignment.AlignmentManager;
import edu.isi.karma.modeling.alignment.LinkIdFactory;
import edu.isi.karma.modeling.ontology.OntologyManager;
import edu.isi.karma.modeling.semantictypes.CRFColumnModel;
import edu.isi.karma.rep.HNode;
import edu.isi.karma.rep.Worksheet;
import edu.isi.karma.rep.alignment.ClassInstanceLink;
import edu.isi.karma.rep.alignment.ColumnNode;
import edu.isi.karma.rep.alignment.ColumnSubClassLink;
import edu.isi.karma.rep.alignment.DataPropertyLink;
import edu.isi.karma.rep.alignment.DataPropertyOfColumnLink;
import edu.isi.karma.rep.alignment.Label;
import edu.isi.karma.rep.alignment.Link;
import edu.isi.karma.rep.alignment.LinkKeyInfo;
import edu.isi.karma.rep.alignment.Node;
import edu.isi.karma.rep.alignment.ObjectPropertyLink;
import edu.isi.karma.rep.alignment.ObjectPropertySpecializationLink;
import edu.isi.karma.rep.alignment.SemanticType;
import edu.isi.karma.rep.alignment.SynonymSemanticTypes;
import edu.isi.karma.view.VWorksheet;
import edu.isi.karma.view.VWorkspace;

public class SetMetaPropertyCommand extends Command {

	private final String hNodeId;
	private final String vWorksheetId;
	private final boolean trainAndShowUpdates;
	private METAPROPERTY_NAME metaPropertyName;
	private final String metaPropertyValue;
	private final String rdfLiteralType;
	
	private CRFColumnModel oldColumnModel;
	private SynonymSemanticTypes oldSynonymTypes;
	private Alignment oldAlignment;
	private DirectedWeightedMultigraph<Node, Link> oldGraph;
	private SemanticType oldType;
	private SemanticType newType;
	
	private final Logger logger = LoggerFactory.getLogger(this.getClass().getSimpleName());
	
	protected SetMetaPropertyCommand(String id, String vWorksheetId, String hNodeId, 
			METAPROPERTY_NAME metaPropertyName, String metaPropertyValue, boolean trainAndShowUpdates,
			String rdfLiteralType) {
		super(id);
		this.hNodeId = hNodeId;
		this.vWorksheetId = vWorksheetId;
		this.trainAndShowUpdates = trainAndShowUpdates;
		this.metaPropertyName = metaPropertyName;
		this.metaPropertyValue = metaPropertyValue;
		this.rdfLiteralType = rdfLiteralType;
		
		addTag(CommandTag.Modeling);
	}

	@Override
	public String getCommandName() {
		return this.getClass().getSimpleName();
	}

	@Override
	public String getTitle() {
		return "Set Semantic Type";
	}

	@Override
	public String getDescription() {
		return null;
	}

	@Override
	public CommandType getCommandType() {
		return CommandType.undoable;
	}

	@SuppressWarnings("unchecked")
	@Override
	public UpdateContainer doIt(VWorkspace vWorkspace) throws CommandException {
		/*** Get the Alignment for this worksheet ***/
		Worksheet worksheet = vWorkspace.getViewFactory().getVWorksheet(vWorksheetId).getWorksheet();
		OntologyManager ontMgr = vWorkspace.getWorkspace().getOntologyManager();
		String alignmentId = AlignmentManager.Instance().constructAlignmentId(vWorkspace.getWorkspace().getId(), vWorksheetId);
		Alignment alignment = AlignmentManager.Instance().getAlignment(alignmentId);
		if (alignment == null) {
			alignment = new Alignment(ontMgr);
			AlignmentManager.Instance().addAlignmentToMap(alignmentId, alignment);
		}
		
		// Save the original alignment for undo
		oldAlignment = alignment.getAlignmentClone();
		oldGraph = (DirectedWeightedMultigraph<Node, Link>)alignment.getGraph().clone();
		
		/*** Add the appropriate nodes and links in alignment graph ***/
		SemanticType newType = null;

		/** Check if a semantic type already exists for the column **/
		ColumnNode existingColumnNode = alignment.getColumnNodeByHNodeId(hNodeId);
		boolean columnNodeAlreadyExisted = false;
		Link oldIncomingLinkToColumnNode = null;
		Node oldDomainNode = null;
		if (existingColumnNode != null) {
			columnNodeAlreadyExisted = true;
			oldIncomingLinkToColumnNode = alignment.getCurrentIncomingLinksToNode(existingColumnNode.getId()).iterator().next();
			oldDomainNode = oldIncomingLinkToColumnNode.getSource();
			if (!rdfLiteralType.equals(existingColumnNode.getRdfLiteralType()))
				existingColumnNode.setRdfLiteralType(rdfLiteralType);
		}
		
		if (metaPropertyName.equals(METAPROPERTY_NAME.isUriOfClass)) {
			ColumnNode columnNode = null;
			Node classNode = alignment.getNodeById(metaPropertyValue);
			if (columnNodeAlreadyExisted) {
				clearOldSemanticTypeLink(oldIncomingLinkToColumnNode, oldDomainNode, alignment, classNode);
				columnNode = existingColumnNode;
			} else
				columnNode = getColumnNode(alignment, vWorkspace.getRepFactory().getHNode(hNodeId)); 
			
			if (classNode == null) {
				Label classNodeLabel = ontMgr.getUriLabel(metaPropertyValue);
				if (classNodeLabel == null) {
					logger.error("URI/ID does not exist in the ontology or model: " + metaPropertyValue);
					return new UpdateContainer(EmptyUpdate.getInstance());
				}
				classNode = alignment.addInternalNode(classNodeLabel);
			}
		
			alignment.addClassInstanceLink(classNode, columnNode, LinkKeyInfo.UriOfInstance);
			alignment.align();
			
			// Create the semantic type object
			newType = new SemanticType(hNodeId, ClassInstanceLink.getFixedLabel(), classNode.getLabel(), SemanticType.Origin.User, 1.0, false);
		} else if (metaPropertyName.equals(METAPROPERTY_NAME.isSpecializationForEdge)) {
			ColumnNode columnNode = null;
			Link propertyLink = alignment.getLinkById(metaPropertyValue);
			if (propertyLink == null) {
				logger.error("Link should exist in the alignment: " + metaPropertyValue);
				return new UpdateContainer(new ErrorUpdate(
						"Error occured while setting the semantic type!"));
			}
			
			Node classInstanceNode = alignment.getNodeById(LinkIdFactory.getLinkSourceId(metaPropertyValue));
			if (columnNodeAlreadyExisted) {
				clearOldSemanticTypeLink(oldIncomingLinkToColumnNode, oldDomainNode, alignment, classInstanceNode);
				columnNode = existingColumnNode;
			} else {
				columnNode = getColumnNode(alignment, vWorkspace.getRepFactory().getHNode(hNodeId));
			}
			

			if (propertyLink instanceof DataPropertyLink) {
				String targetHNodeId = ((ColumnNode) propertyLink.getTarget()).getHNodeId();
				alignment.addDataPropertyOfColumnLink(classInstanceNode, columnNode, targetHNodeId);
				// Create the semantic type object
				newType = new SemanticType(hNodeId, DataPropertyOfColumnLink.getFixedLabel(), classInstanceNode.getLabel(), SemanticType.Origin.User, 1.0, false);
			} else if (propertyLink instanceof ObjectPropertyLink) {
				alignment.addObjectPropertySpecializationLink(classInstanceNode, columnNode, propertyLink);
				// Create the semantic type object
				newType = new SemanticType(hNodeId, ObjectPropertySpecializationLink.getFixedLabel(), classInstanceNode.getLabel(), SemanticType.Origin.User, 1.0, false);
			}
			
			alignment.align();
		} else if (metaPropertyName.equals(METAPROPERTY_NAME.isSubclassOfClass)) {
			ColumnNode columnNode = null;
			Node classNode = alignment.getNodeById(metaPropertyValue);
			if (columnNodeAlreadyExisted) {
				clearOldSemanticTypeLink(oldIncomingLinkToColumnNode, oldDomainNode, alignment, classNode);
				columnNode = existingColumnNode;
			} else
				columnNode = getColumnNode(alignment, vWorkspace.getRepFactory().getHNode(hNodeId)); 
			
			if (classNode == null) {
				Label classNodeLabel = ontMgr.getUriLabel(metaPropertyValue);
				if (classNodeLabel == null) {
					logger.error("URI/ID does not exist in the ontology or model: " + metaPropertyValue);
					return new UpdateContainer(EmptyUpdate.getInstance());
				}
				classNode = alignment.addInternalNode(classNodeLabel);
			}
			alignment.addColumnSubClassOfLink(classNode, columnNode);
			alignment.align();
			
			// Create the semantic type object
			newType = new SemanticType(hNodeId, ColumnSubClassLink.getFixedLabel(), classNode.getLabel(), SemanticType.Origin.User, 1.0, false);
		}
		
		UpdateContainer c = new UpdateContainer();
//		CRFModelHandler crfModelHandler = vWorkspace.getWorkspace().getCrfModelHandler();

		// Save the old SemanticType object and CRF Model for undo
		oldType = worksheet.getSemanticTypes().getSemanticTypeForHNodeId(hNodeId);
		oldColumnModel = worksheet.getCrfModel().getModelByHNodeId(hNodeId);
		oldSynonymTypes = worksheet.getSemanticTypes().getSynonymTypesForHNodeId(newType.getHNodeId());

		// Update the SemanticTypes data structure for the worksheet
		worksheet.getSemanticTypes().addType(newType);

		// Update the synonym semanticTypes
//		worksheet.getSemanticTypes().addSynonymTypesForHNodeId(newType.getHNodeId(), newSynonymTypes);

		if(trainAndShowUpdates) {
			VWorksheet vw = vWorkspace.getViewFactory().getVWorksheet(vWorksheetId);
			c.add(new SemanticTypesUpdate(worksheet, vWorksheetId, alignment));
			try {
				// Add the visualization update
				c.add(new SVGAlignmentUpdate_ForceKarmaLayout(vw, alignment));
			} catch (Exception e) {
				logger.error("Error occured while setting the semantic type!", e);
				return new UpdateContainer(new ErrorUpdate(
						"Error occured while setting the semantic type!"));
			}
			return c;
			
		}
		return c;
	}

	private void clearOldSemanticTypeLink(Link oldIncomingLinkToColumnNode,
			Node oldDomainNode, Alignment alignment, Node newDomainNode) {
		alignment.removeLink(oldIncomingLinkToColumnNode.getId());
		if (oldDomainNode != newDomainNode)
			alignment.removeNode(oldDomainNode.getId());
	}

	@Override
	public UpdateContainer undoIt(VWorkspace vWorkspace) {
		UpdateContainer c = new UpdateContainer();
		Worksheet worksheet = vWorkspace.getViewFactory().getVWorksheet(vWorksheetId).getWorksheet();
		if (oldType == null) {
			worksheet.getSemanticTypes().unassignColumnSemanticType(newType.getHNodeId());
		} else {
			worksheet.getSemanticTypes().addType(oldType);
			worksheet.getSemanticTypes().addSynonymTypesForHNodeId(newType.getHNodeId(), oldSynonymTypes);
		}

		worksheet.getCrfModel().addColumnModel(newType.getHNodeId(), oldColumnModel);

		// Replace the current alignment with the old alignment
		String alignmentId = AlignmentManager.Instance().constructAlignmentId(vWorkspace.getWorkspace().getId(), vWorksheetId);
		AlignmentManager.Instance().addAlignmentToMap(alignmentId, oldAlignment);
		oldAlignment.setGraph(oldGraph);
		
		// Get the alignment update if any
		try {
			c.add(new SemanticTypesUpdate(worksheet, vWorksheetId, oldAlignment));
			c.add(new SVGAlignmentUpdate_ForceKarmaLayout(vWorkspace.getViewFactory().getVWorksheet(vWorksheetId), oldAlignment));
		} catch (Exception e) {
			logger.error("Error occured while unsetting the semantic type!", e);
			return new UpdateContainer(new ErrorUpdate(
					"Error occured while unsetting the semantic type!"));
		}
		return c;
	}
	
	private ColumnNode getColumnNode(Alignment alignment, HNode hNode) {
		String columnName = hNode.getColumnName();
		ColumnNode columnNode = alignment.getColumnNodeByHNodeId(hNodeId);
		
		if (columnNode == null) {
			columnNode = alignment.addColumnNode(hNodeId, columnName, rdfLiteralType);
		} else {
			// Remove old column node if it exists
			alignment.removeNode(columnNode.getId());
			columnNode = alignment.addColumnNode(hNodeId, columnName, rdfLiteralType);
		}
		return columnNode;
	}

}
