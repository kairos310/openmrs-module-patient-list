package org.openmrs.module.patientlist.api.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Query;
import org.hibernate.hql.ast.QuerySyntaxException;
import org.openmrs.Patient;
import org.openmrs.Visit;
import org.openmrs.module.openhmis.commons.api.PagingInfo;
import org.openmrs.module.openhmis.commons.api.entity.impl.BaseObjectDataServiceImpl;
import org.openmrs.module.openhmis.commons.api.entity.model.BaseSerializableOpenmrsMetadata;
import org.openmrs.module.patientlist.api.IPatientListDataService;
import org.openmrs.module.patientlist.api.model.PatientInformationField;
import org.openmrs.module.patientlist.api.model.PatientListData;
import org.openmrs.module.patientlist.api.model.PatientList;
import org.openmrs.module.patientlist.api.model.PatientListCondition;
import org.openmrs.module.patientlist.api.model.PatientListOrder;
import org.openmrs.module.patientlist.api.security.BasicObjectAuthorizationPrivileges;
import org.openmrs.module.patientlist.api.util.ConvertPatientListOperators;
import org.openmrs.module.patientlist.api.util.PatientInformation;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Data service implementation class for {@link PatientListData}'s.
 */
public class PatientListDataServiceImpl extends
        BaseObjectDataServiceImpl<PatientListData, BasicObjectAuthorizationPrivileges>
        implements IPatientListDataService {

	private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

	protected final Log LOG = LogFactory.getLog(this.getClass());

	@Override
	protected BasicObjectAuthorizationPrivileges getPrivileges() {
		return new BasicObjectAuthorizationPrivileges();
	}

	@Override
	protected void validate(PatientListData object) {
		return;
	}

	private PatientInformation patientInformation;

	@Override
	public List<PatientListData> getPatientListData(PatientList patientList, PagingInfo pagingInfo) {
		List<PatientListData> patientListDataSet = new ArrayList<PatientListData>();
		if (patientInformation == null) {
			patientInformation = PatientInformation.getInstance();
		}

		try {
			List<Object> paramValues = new ArrayList<Object>();
			// Create query
			Query query = getRepository().createQuery(constructHqlQuery(patientList, paramValues));
			// set parameters with actual values
			if (paramValues.size() > 0) {
				int index = 0;
				for (Object value : paramValues) {
					query.setParameter(index++, value);
				}
			}

			// set paging params
			Integer count = query.list().size();
			pagingInfo.setTotalRecordCount(count.longValue());
			pagingInfo.setLoadRecordCount(false);

			query = this.createPagingQuery(pagingInfo, query);
			List results = query.list();
			count = results.size();
			if (count > 0) {
				for (Object result : results) {
					Patient patient;
					Visit visit = null;
					if (result instanceof Patient) {
						patient = (Patient)result;
					} else {
						visit = (Visit)result;
						patient = visit.getPatient();
					}

					PatientListData patientListData = new PatientListData(patient, visit, patientList);
					applyTemplates(patientListData);
					patientListDataSet.add(patientListData);
				}
			}
		} catch (QuerySyntaxException ex) {
			LOG.error(ex.getMessage());
		}

		return patientListDataSet;
	}

	/**
	 * Constructs a patient list with given conditions (and ordering)
	 * @param patientList
	 * @param paramValues
	 * @return
	 */
	private String constructHqlQuery(PatientList patientList, List<Object> paramValues) {
		StringBuilder hql = new StringBuilder();
		if (patientList != null && patientList.getPatientListConditions() != null) {
			if (searchField(patientList.getPatientListConditions(), "v.")) {
				// join visit and patient tables
				hql.append("select distinct v from Visit v inner join v.patient as p ");
			} else {
				// use only the patient table
				hql.append("select distinct p from Patient p ");
			}

			// only join person attributes and attribute types if required to
			if (searchField(patientList.getPatientListConditions(), "p.attr")
			        || searchField(patientList.getOrdering(), "p.attr")) {
				hql.append("inner join p.attributes as attr ");
				hql.append("inner join attr.attributeType as attrType ");
			}

			// only join visit attributes and attribute types if required to
			if (searchField(patientList.getPatientListConditions(), "v.attr")
			        || searchField(patientList.getOrdering(), "v.attr")) {
				hql.append("inner join v.attributes as vattr ");
				hql.append("inner join vattr.attributeType as vattrType ");
			}

			// only join names if required
			if (searchMappingField(patientList.getPatientListConditions(), "p.names")
			        || searchMappingField(patientList.getOrdering(), "p.names")) {
				hql.append("inner join p.names as pnames ");
			}

			// only join addresses if required
			if (searchMappingField(patientList.getPatientListConditions(), "p.addresses")
			        || searchMappingField(patientList.getOrdering(), "p.addresses")) {
				hql.append("inner join p.addresses as paddresses ");
			}

			// only join identifiers if required
			if (searchMappingField(patientList.getPatientListConditions(), "p.identifiers")
			        || searchMappingField(patientList.getOrdering(), "p.identifiers")) {
				hql.append("inner join p.identifiers as pidentifiers ");
			}
		}

		// add where clause
		hql.append(" where ");

		// apply patient list conditions
		hql.append("(");
		hql.append(applyPatientListConditions(patientList.getPatientListConditions(), paramValues));
		hql.append(")");

		//apply ordering if any
		hql.append(applyPatientListOrdering(patientList.getOrdering()));

		return hql.toString();
	}

	/**
	 * Parse patient list conditions and add create sub queries to be added on the main HQL query. Parameter search values
	 * will be stored separately and later set when running query.
	 * @param patientListConditions
	 * @param paramValues
	 * @return
	 */
	private String applyPatientListConditions(List<PatientListCondition> patientListConditions,
	        List<Object> paramValues) {
		int count = 0;
		int len = patientListConditions.size();
		StringBuilder hql = new StringBuilder();
		// apply conditions
		for (PatientListCondition condition : patientListConditions) {
			++count;
			if (condition != null && patientInformation.getField(condition.getField()) != null) {
				PatientInformationField patientInformationField =
				        patientInformation.getField(condition.getField());
				String mappingFieldName = patientInformationField.getMappingFieldName();
				if (StringUtils.contains(condition.getField(), "p.attr.")
				        || StringUtils.contains(condition.getField(), "v.attr.")) {
					hql.append(createAttributeSubQueries(condition, paramValues));
				} else if (StringUtils.contains(mappingFieldName, "p.names.")
				        || StringUtils.contains(mappingFieldName, "p.addresses.")
				        || StringUtils.contains(mappingFieldName, "p.identifiers.")) {
					hql.append(createAliasesSubQueries(condition, mappingFieldName, paramValues));
				} else {
					if (mappingFieldName == null) {
						LOG.error("Unknown mapping for field name: " + condition.getField());
						continue;
					}

					hql.append(mappingFieldName);
					hql.append(" ");
					hql.append(ConvertPatientListOperators.convertOperator(condition.getOperator()));
					hql.append(" ");
					if (StringUtils.isNotEmpty(condition.getValue())) {
						hql.append("?");
						if (patientInformationField.getDataType().isAssignableFrom(Date.class)) {
							try {
								paramValues.add(sdf.parse(condition.getValue()));
							} catch (ParseException pex) {
								paramValues.add(condition.getValue());
							}
						} else {
							paramValues.add(condition.getValue());
						}
					}
				}

				if (count < len) {
					hql.append(" and ");
				}
			}
		}

		return hql.toString();
	}

	/**
	 * Creates hql sub-queries for patient and visit attributes. Example: v.attr.bed = 2
	 * @param condition
	 * @param paramValues
	 * @return
	 */
	private String createAttributeSubQueries(PatientListCondition condition, List<Object> paramValues) {
		StringBuilder hql = new StringBuilder();
		String attributeName = condition.getField().split("\\.")[2];
		attributeName = attributeName.replaceAll("_", " ");

		if (StringUtils.contains(condition.getField(), "p.attr.")) {
			hql.append("(attrType.name = ?");
			hql.append(" AND ");
			hql.append("attr.value ");
		} else if (StringUtils.contains(condition.getField(), "v.attr.")) {
			hql.append("(vattrType.name = ?");
			hql.append(" AND ");
			hql.append("vattr.valueReference ");
		}

		hql.append(ConvertPatientListOperators.convertOperator(condition.getOperator()));
		hql.append(" ? ");

		paramValues.add(attributeName);
		paramValues.add(condition.getValue());
		hql.append(") ");

		return hql.toString();
	}

	/**
	 * Creates hql sub-queries for patient aliases (names and addresses). Example: p.names.givenName, p.addresses.address1
	 * @param condition
	 * @param paramValues
	 * @return
	 */
	private String createAliasesSubQueries(PatientListCondition condition,
	        String mappingFieldName, List<Object> paramValues) {
		StringBuilder hql = new StringBuilder();
		String searchField = null;
		if (mappingFieldName != null) {
			// p.names.givenName
			String subs[] = mappingFieldName.split("\\.");
			if (subs != null) {
				searchField = subs[2];
			}
		}

		if (searchField != null) {
			if (StringUtils.contains(mappingFieldName, "p.names.")) {
				hql.append("pnames.");
				hql.append(searchField);
				hql.append(" ");
			} else if (StringUtils.contains(mappingFieldName, "p.addresses.")) {
				hql.append("paddresses.");
				hql.append(searchField);
				hql.append(" ");
			} else if (StringUtils.contains(mappingFieldName, "p.identifiers.")) {
				hql.append("pidentifiers.");
				hql.append(searchField);
				hql.append(" ");
			}

			hql.append(ConvertPatientListOperators.convertOperator(condition.getOperator()));
			hql.append(" ? ");

			paramValues.add(condition.getValue());
			hql.append(" ");
		}

		return hql.toString();
	}

	/**
	 * Order hql query by given fields
	 * @param ordering
	 * @return
	 */
	private String applyPatientListOrdering(List<PatientListOrder> ordering) {
		int count = 0;
		StringBuilder hql = new StringBuilder();
		for (PatientListOrder order : ordering) {
			if (order != null) {
				hql.append(" ");
				if (count++ == 0) {
					hql.append("order by ");
				}

				String mappingFieldName = patientInformation.getField(order.getField()).getMappingFieldName();

				// attributes
				if (StringUtils.contains(order.getField(), "p.attr.")) {
					mappingFieldName = "attrType.name";
				} else if (StringUtils.contains(order.getField(), "v.attr.")) {
					mappingFieldName = "vattrType.name";
				}

				// aliases
				if (StringUtils.contains(mappingFieldName, "p.names.")) {
					mappingFieldName = "pnames." + mappingFieldName.split("\\.")[2];
				} else if (StringUtils.contains(mappingFieldName, "p.addresses.")) {
					mappingFieldName = "paddresses." + mappingFieldName.split("\\.")[2];
				} else if (StringUtils.contains(mappingFieldName, "p.identifiers.")) {
					mappingFieldName = "pidentifiers." + mappingFieldName.split("\\.")[2];
				}

				if (mappingFieldName == null) {
					LOG.error("Unknown mapping for field name: " + order.getField());
					continue;
				}

				hql.append(mappingFieldName);
				hql.append(" ");
				hql.append(order.getSortOrder());
				hql.append(",");
			}
		}

		//remove trailing coma.
		return StringUtils.removeEnd(hql.toString(), ",");
	}

	/**
	 * Searches for a given field in the patient list conditions and ordering.
	 * @param list
	 * @param search
	 * @return
	 */
	private <T> boolean searchField(List<T> list, String search) {
		for (T t : list) {
			if (t == null) {
				continue;
			}

			String field = null;
			if (t instanceof PatientListCondition) {
				field = ((PatientListCondition)t).getField();
			} else if (t instanceof PatientListOrder) {
				field = ((PatientListOrder)t).getField();
			}

			if (field == null) {
				continue;
			}

			if (StringUtils.contains(field, search)) {
				return true;
			}
		}

		return false;
	}

	private <T> boolean searchMappingField(List<T> list, String search) {
		for (T t : list) {
			if (t == null) {
				continue;
			}

			String field = null;
			if (t instanceof PatientListCondition) {
				field = ((PatientListCondition)t).getField();
			} else if (t instanceof PatientListOrder) {
				field = ((PatientListOrder)t).getField();
			}

			if (field == null) {
				continue;
			}

			PatientInformationField patientInformationField = patientInformation.getField(field);
			if (patientInformationField == null) {
				continue;
			}

			String matchField = patientInformationField.getMappingFieldName();
			if (StringUtils.contains(matchField, search)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Apply header and body templates on patient list data
	 * @param patientListData
	 */
	private void applyTemplates(PatientListData patientListData) {
		// apply header template.
		if (patientListData.getPatientList().getHeaderTemplate() != null) {
			patientListData.setHeaderContent(
			        applyTemplates(patientListData.getPatientList().getHeaderTemplate(), patientListData));
		}

		// apply body template
		if (patientListData.getPatientList().getBodyTemplate() != null) {
			patientListData.setBodyContent(
			        applyTemplates(patientListData.getPatientList().getBodyTemplate(), patientListData));
		}
	}

	private String applyTemplates(String template, PatientListData patientListData) {
		String[] fields = StringUtils.substringsBetween(template, "{", "}");
		for (String field : fields) {
			Object value = null;
			PatientInformationField patientInformationField = patientInformation.getField(field);
			if (patientInformationField != null) {
				if (patientListData.getPatient() != null && StringUtils.contains(field, "p.")) {
					value = patientInformationField.getValue(patientListData.getPatient());

				} else if (patientListData.getVisit() != null && StringUtils.contains(field, "v.")) {
					value = patientInformationField.getValue(patientListData.getVisit());
				}
			}

			if (value != null) {
				template = StringUtils.replace(template, "{" + field + "}", value.toString());
			} else {
				template = StringUtils.replace(template, "{" + field + "}", "");
			}
		}

		return template;
	}
}
