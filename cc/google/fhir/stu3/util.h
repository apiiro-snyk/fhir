/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef GOOGLE_FHIR_STU3_UTIL_H_
#define GOOGLE_FHIR_STU3_UTIL_H_

#include "google/protobuf/descriptor.pb.h"
#include "google/protobuf/descriptor.h"
#include "google/protobuf/message.h"
#include "google/protobuf/reflection.h"
#include "absl/strings/match.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/string_view.h"
#include "absl/strings/strip.h"
#include "absl/time/time.h"
#include "google/fhir/status/status.h"
#include "google/fhir/status/statusor.h"
#include "google/fhir/stu3/annotations.h"
#include "google/fhir/stu3/codeable_concepts.h"
#include "google/fhir/stu3/proto_util.h"
#include "google/fhir/systems/systems.h"
#include "proto/annotations.pb.h"
#include "proto/stu3/datatypes.pb.h"
#include "tensorflow/core/lib/core/errors.h"
#include "re2/re2.h"

// Macro for specifying the ContainedResources type within a bundle-like object,
// given the Bundle-like type
#define BUNDLE_CONTAINED_RESOURCE(b)                                  \
  typename std::remove_const<typename std::remove_reference<decltype( \
      std::declval<b>().entry(0).resource())>::type>::type

// Macro for specifying a type contained within a bundle-like object, given
// the Bundle-like type, and a field on the contained resource from that bundle.
// E.g., in a template with a BundleLike type,
// BUNDLE_TYPE(BundleLike, observation)
// will return the Observation type from that bundle.
#define BUNDLE_TYPE(b, r)                                             \
  typename std::remove_const<typename std::remove_reference<decltype( \
      std::declval<b>().entry(0).resource().r())>::type>::type

namespace google {
namespace fhir {
namespace stu3 {

using std::string;
using ::google::fhir::stu3::proto::CodingWithFixedCode;
using ::google::fhir::stu3::proto::CodingWithFixedSystem;
using ::google::fhir::stu3::proto::Extension;
using ::google::fhir::stu3::proto::Reference;

// Extract first code value with a given system.
// Returns NOT_FOUND if none exists.
// TODO: use GetOnlyCodeWithSystem, making this return
// ALREADY_EXISTS if more than one code exists in that system.
template <typename CodeableConceptLike>
StatusOr<string> ExtractCodeBySystem(
    const CodeableConceptLike& codeable_concept,
    absl::string_view system_value) {
  const std::vector<string>& codes =
      GetCodesWithSystem(codeable_concept, system_value);
  if (codes.size() == 0) {
    return tensorflow::errors::NotFound("No code from system: ", system_value);
  }
  return codes.front();
}

// Extract the icd code for the given schemes.
template <typename CodeableConceptLike>
StatusOr<string> ExtractIcdCode(const CodeableConceptLike& codeable_concept,
                                const std::vector<string>& schemes) {
  bool found_response = false;
  StatusOr<string> result;
  for (size_t i = 0; i < schemes.size(); i++) {
    StatusOr<string> s = ExtractCodeBySystem(codeable_concept, schemes[i]);

    if (s.status().code() == ::tensorflow::errors::Code::ALREADY_EXISTS) {
      // Multiple codes, so we can return an error already.
      return s;
    } else if (s.ok()) {
      if (found_response) {
        // We found _another_ code. That shouldn't have happened.
        return ::tensorflow::errors::AlreadyExists("Found more than one code");
      } else {
        result = s;
        found_response = true;
      }
    }
  }
  if (found_response) {
    return result;
  } else {
    return ::tensorflow::errors::NotFound(
        "No ICD code with the provided schemes in concept.");
  }
}

template <typename R>
stu3::proto::Meta* MutableMetadataFromResource(R* resource) {
  return resource->mutable_meta();
}

StatusOr<Reference> ReferenceStringToProto(const string& input);
// Return the full string representation of a reference.
StatusOr<string> ReferenceProtoToString(const Reference& reference);

// Builds an absl::Time from a time-like fhir Element.
// Must have a value_us field.
template <class T>
absl::Time GetTimeFromTimelikeElement(const T& timelike) {
  return absl::FromUnixMicros(timelike.value_us());
}

absl::Duration GetDurationFromTimelikeElement(
    const stu3::proto::DateTime& datetime);

Status GetTimezone(const string& timezone_str, absl::TimeZone* tz);

// Builds a absl::Time from a time-like fhir Element, corresponding to the
// smallest time greater than this time element. For elements with DAY
// precision, for example, this will be 86400 seconds past value_us of this
// field.
template <class T>
absl::Time GetUpperBoundFromTimelikeElement(const T& timelike) {
  return absl::FromUnixMicros(timelike.value_us()) +
         GetDurationFromTimelikeElement(timelike);
}

// Populates the resource oneof on ContainedResource with the passed-in
// resource.
template <typename ContainedResourceLike>
Status SetContainedResource(const ::google::protobuf::Message& resource,
                            ContainedResourceLike* contained) {
  const ::google::protobuf::OneofDescriptor* resource_oneof =
      ContainedResourceLike::descriptor()->FindOneofByName("oneof_resource");
  const ::google::protobuf::FieldDescriptor* resource_field = nullptr;
  for (int i = 0; i < resource_oneof->field_count(); i++) {
    const ::google::protobuf::FieldDescriptor* field = resource_oneof->field(i);
    if (field->cpp_type() != ::google::protobuf::FieldDescriptor::CPPTYPE_MESSAGE) {
      return ::tensorflow::errors::InvalidArgument(
          absl::StrCat("Field ", field->full_name(), "is not a message"));
    }
    if (field->message_type()->name() == resource.GetDescriptor()->name()) {
      resource_field = field;
    }
  }
  if (resource_field == nullptr) {
    return ::tensorflow::errors::InvalidArgument(
        absl::StrCat("Resource type ", resource.GetDescriptor()->name(),
                     " not found in fhir::Bundle::Entry::resource"));
  }
  const ::google::protobuf::Reflection* ref = contained->GetReflection();
  ref->MutableMessage(contained, resource_field)->CopyFrom(resource);
  return Status::OK();
}

template <typename ContainedResourceLike>
StatusOr<const ::google::protobuf::Message*> GetContainedResource(
    const ContainedResourceLike& contained) {
  const ::google::protobuf::Reflection* ref = contained.GetReflection();
  // Get the resource field corresponding to this resource.
  const ::google::protobuf::OneofDescriptor* resource_oneof =
      contained.GetDescriptor()->FindOneofByName("oneof_resource");
  const ::google::protobuf::FieldDescriptor* field =
      contained.GetReflection()->GetOneofFieldDescriptor(contained,
                                                         resource_oneof);
  if (!field) {
    return ::tensorflow::errors::NotFound("No Bundle Resource found");
  }
  return &(ref->GetMessage(contained, field));
}

// Returns the input resource, wrapped in a ContainedResource
template <typename ContainedResourceLike>
StatusOr<ContainedResourceLike> WrapContainedResource(
    const ::google::protobuf::Message& resource) {
  ContainedResourceLike contained_resource;
  TF_RETURN_IF_ERROR(SetContainedResource(resource, &contained_resource));
  return contained_resource;
}

StatusOr<string> GetResourceId(const ::google::protobuf::Message& message);

template <typename BundleLike, typename PatientLike>
Status GetPatient(const BundleLike& bundle, const PatientLike** patient) {
  bool found = false;
  for (const auto& entry : bundle.entry()) {
    if (entry.resource().has_patient()) {
      if (found) {
        return ::tensorflow::errors::AlreadyExists(
            "Found more than one patient in bundle");
      }
      *patient = &entry.resource().patient();
      found = true;
    }
  }
  if (found) {
    return Status::OK();
  } else {
    return ::tensorflow::errors::NotFound("No patient in bundle.");
  }
}

template <typename BundleLike,
          typename PatientLike = BUNDLE_TYPE(BundleLike, patient)>
StatusOr<const PatientLike*> GetPatient(const BundleLike& bundle) {
  const PatientLike* patient;
  FHIR_RETURN_IF_ERROR(GetPatient(bundle, &patient));
  return patient;
}

// Returns a reference, e.g. "Encounter/1234" for a FHIR resource.
string GetReferenceToResource(const ::google::protobuf::Message& message);

// Returns a typed Reference for a FHIR resource.  If the message is not a FHIR
// resource, an error will be returned.
StatusOr<Reference> GetTypedReferenceToResource(
    const ::google::protobuf::Message& message);

// Extract the value of a Decimal field as a double.
Status GetDecimalValue(const stu3::proto::Decimal& decimal, double* value);

// Extracts and returns the FHIR metadata from a resource
template <typename R>
const stu3::proto::Meta& GetMetadataFromResource(const R& resource) {
  return resource.meta();
}

// Extracts and returns the FHIR resource from a bundle entry.
template <typename EntryLike>
Status GetResourceFromBundleEntry(const EntryLike& entry,
                                  const ::google::protobuf::Message** result) {
  auto got_value = GetContainedResource(entry.resource());
  TF_RETURN_IF_ERROR(got_value.status());
  *result = got_value.ValueOrDie();
  return Status::OK();
}

// Extracts and returns the FHIR extension list from the resource field in
// a bundle entry.
template <typename EntryLike>
StatusOr<const ::google::protobuf::RepeatedFieldRef<Extension>>
GetResourceExtensionsFromBundleEntry(const EntryLike& entry) {
  const ::google::protobuf::Message* resource;
  TF_RETURN_IF_ERROR(GetResourceFromBundleEntry(entry, &resource));
  const ::google::protobuf::Reflection* ref = resource->GetReflection();
  // Get the bundle field corresponding to this resource.
  const ::google::protobuf::FieldDescriptor* field =
      resource->GetDescriptor()->FindFieldByName("extension");
  if (field == nullptr) {
    return ::tensorflow::errors::NotFound("No extension field.");
  }
  return ref->GetRepeatedFieldRef<stu3::proto::Extension>(*resource, field);
}

}  // namespace stu3
}  // namespace fhir
}  // namespace google

#endif  // GOOGLE_FHIR_STU3_UTIL_H_
